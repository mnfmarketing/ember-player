package app.ember.cast

import android.content.Context
import android.net.Uri
import app.ember.drive.DriveApi
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Tells Google's Cast framework to use the standard media receiver. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** Name of the device Ember is casting to, or null when playing on the phone. */
object CastStatus {
    val device = MutableStateFlow<String?>(null)
    const val RECEIVER_ID = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
}

/**
 * A tiny web server on your phone. A Chromecast can only play audio it can fetch
 * over the network, so local and Google Drive files are shared to it on your Wi-Fi,
 * behind a random, unguessable address that changes every time casting starts.
 */
class LocalMediaServer(
    private val context: Context,
    private val driveToken: () -> String?,
) {
    sealed class Source {
        abstract val mime: String
        data class Content(val uri: Uri, override val mime: String) : Source()
        data class FilePath(val path: String, override val mime: String) : Source()
        data class Drive(val fileId: String, override val mime: String) : Source()
    }

    private var server: ServerSocket? = null
    private var secret = newSecret()
    private val sources = ConcurrentHashMap<String, Source>()
    private val pool = Executors.newCachedThreadPool()

    private fun newSecret() = UUID.randomUUID().toString().replace("-", "").take(20)

    @Synchronized
    fun start() {
        if (server != null) return
        secret = newSecret()
        val s = ServerSocket(0)
        server = s
        Thread {
            while (!s.isClosed) {
                try {
                    val client = s.accept()
                    pool.execute { handle(client) }
                } catch (e: Exception) {
                    break
                }
            }
        }.apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        try {
            server?.close()
        } catch (e: Exception) {
        }
        server = null
        sources.clear()
    }

    /** Address the Chromecast can fetch, or null if the phone isn't on Wi-Fi. */
    fun urlFor(key: String, source: Source): String? {
        val s = server ?: return null
        val ip = wifiAddress() ?: return null
        val safeKey = key.replace(Regex("[^A-Za-z0-9_-]"), "_")
        sources[safeKey] = source
        return "http://$ip:${s.localPort}/$secret/$safeKey"
    }

    private fun wifiAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 30_000
                val input = sock.getInputStream()
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].trimStart('/').split("/")
                val out = sock.getOutputStream()
                if (path.size != 2 || path[0] != secret) return respond(out, 404, "Not Found")
                val source = sources[path[1]] ?: return respond(out, 404, "Not Found")
                val headOnly = method == "HEAD"
                when (source) {
                    is Source.Content -> {
                        val pfd = context.contentResolver.openFileDescriptor(source.uri, "r") ?: return respond(out, 404, "Not Found")
                        pfd.use { fd ->
                            FileInputStream(fd.fileDescriptor).use { fis ->
                                serveStream(out, fis, fd.statSize, source.mime, headers["range"], headOnly) { skip -> fis.channel.position(skip) }
                            }
                        }
                    }
                    is Source.FilePath -> {
                        val f = File(source.path)
                        if (!f.exists()) return respond(out, 404, "Not Found")
                        FileInputStream(f).use { fis ->
                            serveStream(out, fis, f.length(), source.mime, headers["range"], headOnly) { skip -> fis.channel.position(skip) }
                        }
                    }
                    is Source.Drive -> proxyDrive(out, source, headers["range"], headOnly)
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun serveStream(
        out: OutputStream,
        input: InputStream,
        size: Long,
        mime: String,
        range: String?,
        headOnly: Boolean,
        seek: (Long) -> Unit,
    ) {
        var start = 0L
        var end = size - 1
        val partial = range != null && range.startsWith("bytes=") && size > 0
        if (partial) {
            val spec = range!!.removePrefix("bytes=").split(",")[0].split("-")
            val a = spec.getOrNull(0)?.trim().orEmpty()
            val b = spec.getOrNull(1)?.trim().orEmpty()
            if (a.isEmpty() && b.isNotEmpty()) {
                start = (size - b.toLong()).coerceAtLeast(0)
            } else {
                start = a.toLongOrNull() ?: 0L
                if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1)
            }
            if (start >= size) {
                writeHead(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$size"))
                return
            }
        }
        val length = end - start + 1
        val h = linkedMapOf(
            "Content-Type" to mime,
            "Accept-Ranges" to "bytes",
            "Content-Length" to length.toString(),
            "Access-Control-Allow-Origin" to "*",
        )
        if (partial) h["Content-Range"] = "bytes $start-$end/$size"
        writeHead(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", h)
        if (headOnly) return
        if (start > 0) seek(start)
        copy(input, out, length)
    }

    private fun proxyDrive(out: OutputStream, source: Source.Drive, range: String?, headOnly: Boolean) {
        val token = driveToken() ?: return respond(out, 503, "Unavailable")
        val conn = (URL(DriveApi.mediaUrl(source.fileId)).openConnection() as HttpURLConnection).apply {
            requestMethod = if (headOnly) "HEAD" else "GET"
            setRequestProperty("Authorization", "Bearer $token")
            if (range != null) setRequestProperty("Range", range)
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            val code = conn.responseCode
            val h = linkedMapOf(
                "Content-Type" to source.mime,
                "Accept-Ranges" to "bytes",
                "Access-Control-Allow-Origin" to "*",
            )
            conn.getHeaderField("Content-Length")?.let { h["Content-Length"] = it }
            conn.getHeaderField("Content-Range")?.let { h["Content-Range"] = it }
            writeHead(out, code, if (code == 206) "Partial Content" else "OK", h)
            if (!headOnly && code in 200..299) conn.inputStream.use { copy(it, out, Long.MAX_VALUE) }
        } finally {
            conn.disconnect()
        }
    }

    private fun copy(input: InputStream, out: OutputStream, max: Long) {
        val buf = ByteArray(64 * 1024)
        var left = max
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            left -= n
        }
        out.flush()
    }

    private fun respond(out: OutputStream, code: Int, msg: String) {
        writeHead(out, code, msg, mapOf("Content-Length" to "0"))
    }

    private fun writeHead(out: OutputStream, code: Int, msg: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $msg\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    companion object {
        fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "m4b", "mp4", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "audio/mpeg"
        }
    }
}
