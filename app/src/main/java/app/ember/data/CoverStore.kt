package app.ember.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Saves cover art, extracts accent colors and looks up covers on Open Library. */
class CoverStore(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "covers").apply { mkdirs() }

    data class SavedCover(val path: String, val color: Int?)

    fun saveBytes(bookId: String, bytes: ByteArray): SavedCover? {
        val bmp = decodeSampled(bytes, 1200) ?: return null
        return saveBitmap(bookId, bmp)
    }

    fun saveFromUri(bookId: String, uri: Uri): SavedCover? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        bytes?.let { saveBytes(bookId, it) }
    } catch (e: Exception) {
        null
    }

    private fun saveBitmap(bookId: String, bmp: Bitmap): SavedCover? = try {
        // New file name each time so image caches refresh.
        dir.listFiles { f -> f.name.startsWith("$bookId-") }?.forEach { it.delete() }
        val file = File(dir, "$bookId-${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        SavedCover(file.absolutePath, accentFrom(bmp))
    } catch (e: Exception) {
        null
    }

    suspend fun download(bookId: String, url: String): SavedCover? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Ember-Audiobooks/1.0")
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            saveBytes(bookId, bytes)
        } catch (e: Exception) {
            null
        }
    }

    /** Returns large cover image URLs from Open Library for a title/author. */
    suspend fun searchOpenLibrary(title: String, author: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val q = StringBuilder("https://openlibrary.org/search.json?limit=20&fields=cover_i")
            q.append("&title=").append(URLEncoder.encode(title, "UTF-8"))
            if (author.isNotBlank()) q.append("&author=").append(URLEncoder.encode(author, "UTF-8"))
            val conn = (URL(q.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Ember-Audiobooks/1.0")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val docs = JSONObject(text).optJSONArray("docs") ?: return@withContext emptyList()
            val out = LinkedHashSet<String>()
            for (i in 0 until docs.length()) {
                val id = docs.getJSONObject(i).optLong("cover_i", -1L)
                if (id > 0) out += "https://covers.openlibrary.org/b/id/$id-L.jpg"
            }
            out.toList().take(12)
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        fun decodeSampled(bytes: ByteArray, maxSize: Int): Bitmap? {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) return null
            var sample = 1
            while (opts.outWidth / sample > maxSize || opts.outHeight / sample > maxSize) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        fun decodeFile(path: String, maxSize: Int): Bitmap? = try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            var sample = 1
            while (opts.outWidth / sample > maxSize || opts.outHeight / sample > maxSize) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        }

        /** A bright, saturated accent that reads well on a near-black UI. */
        fun accentFrom(bmp: Bitmap): Int? = try {
            val palette = Palette.from(bmp).maximumColorCount(16).generate()
            val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch
                ?: palette.mutedSwatch ?: palette.dominantSwatch
            swatch?.rgb?.let { tuneAccent(it) }
        } catch (e: Exception) {
            null
        }

        fun tuneAccent(color: Int): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[1] = hsl[1].coerceIn(0.35f, 0.85f)
            hsl[2] = hsl[2].coerceIn(0.58f, 0.74f)
            return ColorUtils.HSLToColor(hsl)
        }
    }
}
