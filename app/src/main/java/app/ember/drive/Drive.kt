package app.ember.drive

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Drive access using Google's authorization API.
 * Read-only: Ember never changes anything in your Drive.
 */
class DriveAuth(private val context: Context) {

    enum class Status { DISCONNECTED, CONNECTED, NEEDS_CONSENT, ERROR }

    private val _status = MutableStateFlow(Status.DISCONNECTED)
    val status: StateFlow<Status> = _status
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val prefs = context.getSharedPreferences("drive", Context.MODE_PRIVATE)
    private val lock = Mutex()
    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L

    init {
        if (prefs.getBoolean("connected", false)) _status.value = Status.CONNECTED
    }

    val isConnected: Boolean get() = prefs.getBoolean("connected", false)

    private val request: AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /**
     * Starts sign-in. Returns a PendingIntent to show Google's consent screen,
     * or null if access was granted without needing one.
     */
    suspend fun begin(activity: Activity): PendingIntent? {
        return try {
            val result = Identity.getAuthorizationClient(activity).authorize(request).await()
            if (result.hasResolution()) {
                result.pendingIntent
            } else {
                accept(result)
                null
            }
        } catch (e: Exception) {
            fail(e)
            null
        }
    }

    /** Finishes sign-in after the consent screen returns. */
    fun complete(data: Intent?): Boolean = try {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        accept(result)
        true
    } catch (e: Exception) {
        fail(e)
        false
    }

    private fun accept(result: AuthorizationResult) {
        token = result.accessToken
        tokenAt = System.currentTimeMillis()
        prefs.edit().putBoolean("connected", true).apply()
        _status.value = Status.CONNECTED
        _lastError.value = null
    }

    private fun fail(e: Exception) {
        _lastError.value = e.message ?: e.javaClass.simpleName
        _status.value = Status.ERROR
    }

    fun disconnect() {
        token = null
        prefs.edit().putBoolean("connected", false).apply()
        _status.value = Status.DISCONNECTED
    }

    /** A fresh access token, renewed quietly when needed. Null if you need to reconnect. */
    suspend fun token(): String? = lock.withLock {
        if (!isConnected) return null
        val t = token
        if (t != null && System.currentTimeMillis() - tokenAt < 45 * 60_000) return t
        try {
            val result = Identity.getAuthorizationClient(context).authorize(request).await()
            if (result.hasResolution()) {
                _status.value = Status.NEEDS_CONSENT
                null
            } else {
                token = result.accessToken
                tokenAt = System.currentTimeMillis()
                _status.value = Status.CONNECTED
                token
            }
        } catch (e: Exception) {
            fail(e)
            null
        }
    }

    /** For the player's loader thread. */
    fun tokenBlocking(): String? = runBlocking { token() }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    }
}

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
) {
    val isFolder get() = mimeType == "application/vnd.google-apps.folder"
}

class DriveApi(private val auth: DriveAuth) {

    suspend fun list(folderId: String, foldersOnly: Boolean = false): List<DriveFile> = withContext(Dispatchers.IO) {
        val token = auth.token() ?: throw IllegalStateException("Google Drive needs reconnecting")
        val out = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            var q = "'$folderId' in parents and trashed = false"
            if (foldersOnly) q += " and mimeType = 'application/vnd.google-apps.folder'"
            val url = StringBuilder("https://www.googleapis.com/drive/v3/files?pageSize=1000")
                .append("&fields=").append(URLEncoder.encode("nextPageToken,files(id,name,mimeType,size)", "UTF-8"))
                .append("&orderBy=").append(URLEncoder.encode("folder,name_natural", "UTF-8"))
                .append("&supportsAllDrives=true&includeItemsFromAllDrives=true")
                .append("&q=").append(URLEncoder.encode(q, "UTF-8"))
            pageToken?.let { url.append("&pageToken=").append(URLEncoder.encode(it, "UTF-8")) }
            val json = getJson(url.toString(), token)
            val files = json.optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    out += DriveFile(f.getString("id"), f.optString("name"), f.optString("mimeType"), f.optString("size", "0").toLongOrNull() ?: 0)
                }
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        out
    }

    /** Mime type for casting, from the file's own type or its name. */
    suspend fun mimeType(fileId: String): String = withContext(Dispatchers.IO) {
        val token = auth.token() ?: return@withContext "audio/mpeg"
        try {
            val j = getJson("https://www.googleapis.com/drive/v3/files/$fileId?fields=name,mimeType&supportsAllDrives=true", token)
            val mime = j.optString("mimeType")
            if (mime.startsWith("audio/") && mime != "audio/x-m4b") mime else app.ember.cast.LocalMediaServer.mimeForName(j.optString("name"))
        } catch (e: Exception) {
            "audio/mpeg"
        }
    }

    suspend fun name(fileId: String): String = withContext(Dispatchers.IO) {
        if (fileId == "root") return@withContext "My Drive"
        val token = auth.token() ?: return@withContext "Drive folder"
        try {
            getJson("https://www.googleapis.com/drive/v3/files/$fileId?fields=name&supportsAllDrives=true", token).optString("name", "Drive folder")
        } catch (e: Exception) {
            "Drive folder"
        }
    }

    suspend fun download(fileId: String): ByteArray? = withContext(Dispatchers.IO) {
        val token = auth.token() ?: return@withContext null
        try {
            val conn = open(mediaUrl(fileId), token)
            conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
        } catch (e: Exception) {
            null
        }
    }

    /** Reads [length] bytes at [offset] (for reading chapter markers without downloading the whole file). */
    fun readRange(fileId: String, token: String, offset: Long, length: Int): ByteArray {
        val conn = open(mediaUrl(fileId), token, "bytes=$offset-${offset + length - 1}")
        try {
            return conn.inputStream.use { readUpTo(it, length) }
        } finally {
            conn.disconnect()
        }
    }

    private fun readUpTo(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return if (read == length) buf else buf.copyOf(read)
    }

    private fun open(url: String, token: String, range: String? = null): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            if (range != null) setRequestProperty("Range", range)
        }
        if (conn.responseCode !in 200..299) {
            val code = conn.responseCode
            conn.disconnect()
            throw IllegalStateException("Drive returned $code")
        }
        return conn
    }

    private fun getJson(url: String, token: String): JSONObject {
        val conn = open(url, token)
        try {
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun mediaUrl(fileId: String) = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true"
    }
}
