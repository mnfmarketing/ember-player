package app.ember.sync

import app.ember.drive.DriveAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Where the sync files live: Google Drive's hidden app folder, or a plain folder for the emulator checks. */
interface SyncRemote {
    /** File name → a stamp that changes whenever the file does. */
    suspend fun list(): Map<String, String>

    suspend fun read(name: String): String?

    /** Writes [text] and returns the file's new stamp. */
    suspend fun write(name: String, text: String): String?

    /** The file's current stamp (null if it doesn't exist). */
    suspend fun stamp(name: String): String?
}

class SyncError(message: String) : Exception(message)

/**
 * Drive's appDataFolder: a hidden folder only Ember can see. It doesn't count toward anything you
 * browse in Drive, and nothing else in your Drive is touched.
 */
class DriveAppDataRemote(private val auth: DriveAuth) : SyncRemote {

    private val ids = HashMap<String, String>()

    private suspend fun token(): String = auth.token() ?: throw SyncError("Google Drive needs reconnecting for sync")

    override suspend fun list(): Map<String, String> = withContext(Dispatchers.IO) {
        val t = token()
        val out = HashMap<String, String>()
        val newest = HashMap<String, String>()
        var page: String? = null
        do {
            val url = StringBuilder(FILES)
                .append("?spaces=appDataFolder&pageSize=1000")
                .append("&fields=").append(enc("nextPageToken,files(id,name,version,modifiedTime)"))
            page?.let { url.append("&pageToken=").append(enc(it)) }
            val j = json(request("GET", url.toString(), t))
            val files = j.optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    val name = f.optString("name")
                    val mod = f.optString("modifiedTime")
                    // Two devices creating the same file at once leaves two copies: use the newest.
                    if (newest[name] == null || mod > newest.getValue(name)) {
                        newest[name] = mod
                        ids[name] = f.getString("id")
                        out[name] = f.optString("version")
                    }
                }
            }
            page = j.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null)
        out
    }

    private suspend fun idOf(name: String): String? {
        ids[name]?.let { return it }
        list()
        return ids[name]
    }

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        val id = idOf(name) ?: return@withContext null
        try {
            text(request("GET", "$FILES/$id?alt=media", token()))
        } catch (e: NotFound) {
            ids.remove(name)
            null
        }
    }

    override suspend fun stamp(name: String): String? = withContext(Dispatchers.IO) {
        val id = idOf(name) ?: return@withContext null
        try {
            json(request("GET", "$FILES/$id?fields=version", token())).optString("version")
        } catch (e: NotFound) {
            ids.remove(name)
            null
        }
    }

    override suspend fun write(name: String, text: String): String? = withContext(Dispatchers.IO) {
        val t = token()
        val id = idOf(name)
        if (id != null) {
            try {
                // HttpURLConnection can't send PATCH; Google's APIs accept POST with this header instead.
                val conn = request(
                    "POST", "$UPLOAD/$id?uploadType=media&fields=version", t,
                    body = text.toByteArray(), type = "application/json", override = "PATCH",
                )
                return@withContext json(conn).optString("version")
            } catch (e: NotFound) {
                ids.remove(name)
            }
        }
        val boundary = "ember" + System.nanoTime()
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put("appDataFolder")).toString()
        val body = buildString {
            append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").append(meta).append("\r\n")
            append("--$boundary\r\nContent-Type: application/json\r\n\r\n").append(text).append("\r\n")
            append("--$boundary--\r\n")
        }
        val j = json(request("POST", "$UPLOAD?uploadType=multipart&fields=id,version", t, body = body.toByteArray(), type = "multipart/related; boundary=$boundary"))
        ids[name] = j.getString("id")
        j.optString("version")
    }

    private class NotFound : Exception()

    private fun request(method: String, url: String, token: String, body: ByteArray? = null, type: String? = null, override: String? = null): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            if (override != null) setRequestProperty("X-HTTP-Method-Override", override)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", type ?: "application/octet-stream")
                setFixedLengthStreamingMode(body.size)
                outputStream.use { it.write(body) }
            }
        }
        val code = conn.responseCode
        if (code == 404) {
            conn.disconnect()
            throw NotFound()
        }
        if (code !in 200..299) {
            val detail = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
            } catch (e: Exception) {
                null
            }
            conn.disconnect()
            throw SyncError(
                when (code) {
                    401, 403 -> "Google Drive turned sync down ($code). Try turning sync off and on again."
                    else -> "Google Drive returned $code" + (detail?.let { ": $it" } ?: "")
                },
            )
        }
        return conn
    }

    private fun text(conn: HttpURLConnection): String = try {
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }

    private fun json(conn: HttpURLConnection) = JSONObject(text(conn))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    }
}

/** A plain folder standing in for Drive (the emulator checks). */
class FolderRemote(private val dir: File) : SyncRemote {

    init {
        dir.mkdirs()
    }

    private fun stampOf(f: File) = "${f.lastModified()}-${f.length()}"

    override suspend fun list(): Map<String, String> = withContext(Dispatchers.IO) {
        (dir.listFiles() ?: emptyArray()).filter { it.isFile }.associate { it.name to stampOf(it) }
    }

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        File(dir, name).takeIf { it.exists() }?.readText()
    }

    override suspend fun write(name: String, text: String): String? = withContext(Dispatchers.IO) {
        val f = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
        // Make sure the stamp changes even within the same millisecond.
        f.setLastModified(maxOf(System.currentTimeMillis(), f.lastModified() + 1))
        stampOf(f)
    }

    override suspend fun stamp(name: String): String? = withContext(Dispatchers.IO) {
        File(dir, name).takeIf { it.exists() }?.let { stampOf(it) }
    }
}
