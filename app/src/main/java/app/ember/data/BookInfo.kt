package app.ember.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Book details from Audible's public catalog (the same source Audiobookshelf uses):
 * series and number, narrator, description, cover and length.
 */
object BookInfo {

    data class Match(
        val asin: String,
        val title: String,
        val subtitle: String,
        val authors: String,
        val narrators: String,
        val series: String,
        val seriesIndex: Float?,
        val description: String,
        val coverUrl: String?,
        val lengthMs: Long,
        val year: String,
    )

    suspend fun search(title: String, author: String, keywords: String? = null): List<Match> = withContext(Dispatchers.IO) {
        val q = StringBuilder("https://api.audible.com/1.0/catalog/products?num_results=12&products_sort_by=Relevance")
        q.append("&response_groups=contributors,product_desc,product_attrs,media,series")
        q.append("&image_sizes=500,1024")
        if (keywords != null) {
            q.append("&keywords=").append(enc(keywords))
        } else {
            q.append("&title=").append(enc(cleanTitle(title)))
            if (author.isNotBlank()) q.append("&author=").append(enc(author))
        }
        val text = try {
            val conn = (URL(q.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Ember-Audiobooks/1.0")
                setRequestProperty("Accept", "application/json")
            }
            conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
        } catch (e: Exception) {
            return@withContext emptyList()
        }
        val products = runCatching { JSONObject(text).optJSONArray("products") }.getOrNull() ?: return@withContext emptyList()
        (0 until products.length()).mapNotNull { i ->
            val p = products.optJSONObject(i) ?: return@mapNotNull null
            val series = p.optJSONArray("series")?.optJSONObject(0)
            val images = p.optJSONObject("product_images")
            Match(
                asin = p.optString("asin"),
                title = p.optString("title"),
                subtitle = p.optString("subtitle"),
                authors = names(p, "authors"),
                narrators = names(p, "narrators"),
                series = series?.optString("title").orEmpty(),
                seriesIndex = series?.optString("sequence")?.toFloatOrNull(),
                description = stripHtml(p.optString("publisher_summary").ifBlank { p.optString("merchandising_summary") }),
                coverUrl = images?.optString("1024")?.takeIf { it.isNotBlank() } ?: images?.optString("500")?.takeIf { it.isNotBlank() },
                lengthMs = p.optLong("runtime_length_min") * 60_000L,
                year = p.optString("release_date").take(4),
            )
        }.filter { it.title.isNotBlank() }
    }

    /**
     * The match to trust without asking: the title lines up, the author lines up (when known)
     * and the length is close (when known). Null when nothing is clear enough.
     */
    fun confident(book: BookEntity, matches: List<Match>): Match? = matches.firstOrNull { m ->
        val titleOk = norm(m.title).let { t -> t.isNotEmpty() && (t == norm(cleanTitle(book.title)) || t.startsWith(norm(cleanTitle(book.title))) || norm(cleanTitle(book.title)).startsWith(t)) }
        val authorOk = book.author.isBlank() || norm(m.authors).contains(norm(book.author).take(12))
        val lengthOk = book.durationMs <= 0 || book.isExternal || m.lengthMs <= 0 ||
            abs(m.lengthMs - book.durationMs).toDouble() / book.durationMs < 0.08
        titleOk && authorOk && lengthOk
    }

    /** "Book 01 - The Name (Unabridged)" → "The Name". */
    fun cleanTitle(t: String): String = t
        .replace(Regex("\\((un)?abridged\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(book|vol\\.?|volume)\\s*\\d+\\s*[-:.]\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^\\d+\\s*[-.]\\s*"), "")
        .trim()

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun names(p: JSONObject, key: String): String {
        val a = p.optJSONArray(key) ?: return ""
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }.joinToString(", ")
    }

    private fun stripHtml(html: String): String =
        android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
