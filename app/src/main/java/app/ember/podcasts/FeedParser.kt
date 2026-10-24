package app.ember.podcasts

import android.text.Html
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

data class ParsedFeed(
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String,
    val episodes: List<ParsedEpisode>,
    val category: String = "",
)

data class ParsedEpisode(
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val durationMs: Long,
    val publishedAt: Long,
    val chaptersUrl: String = "",
)

data class SearchResult(
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String,
)

object Feeds {

    private const val UA = "Ember-Audiobooks/1.0 (Android podcast player)"

    fun podcastId(feedUrl: String): String =
        "pod-" + UUID.nameUUIDFromBytes(feedUrl.trim().toByteArray()).toString()

    fun episodeId(podcastId: String, guid: String): String =
        "ep-" + UUID.nameUUIDFromBytes("$podcastId|$guid".toByteArray()).toString()

    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return conn
                conn.disconnect()
                current = URL(URL(current), loc).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    fun fetch(feedUrl: String): ParsedFeed {
        val conn = open(feedUrl)
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("Feed returned ${conn.responseCode}")
            return conn.inputStream.use { parse(it) }
        } finally {
            conn.disconnect()
        }
    }

    /** Apple Podcasts chart categories (name to Apple genre id). */
    val genres: List<Pair<String, Int>> = listOf(
        "True Crime" to 1488,
        "Comedy" to 1303,
        "News" to 1489,
        "Society & Culture" to 1324,
        "History" to 1487,
        "Fiction" to 1483,
        "Arts" to 1301,
        "Business" to 1321,
        "Education" to 1304,
        "Health & Fitness" to 1512,
        "Science" to 1533,
        "Technology" to 1318,
        "Sports" to 1545,
        "TV & Film" to 1309,
        "Religion & Spirituality" to 1314,
        "Kids & Family" to 1305,
        "Leisure" to 1502,
        "Music" to 1310,
    )

    /**
     * Top shows from Apple Podcasts' charts, overall ([genreId] null) or for one category.
     * Falls back to a category search if the chart can't be read.
     */
    fun topCharts(genreId: Int?, genreName: String?): List<SearchResult> {
        val ids = try {
            chartIds(genreId)
        } catch (e: Exception) {
            emptyList()
        }
        if (ids.isNotEmpty()) {
            val looked = try {
                lookup(ids)
            } catch (e: Exception) {
                emptyList()
            }
            if (looked.isNotEmpty()) return looked
        }
        return if (genreName != null) search(genreName) else search("podcast")
    }

    private fun chartIds(genreId: Int?): List<String> {
        val url = if (genreId == null) {
            "https://rss.applemarketingtools.com/api/v2/us/podcasts/top/50/podcasts.json"
        } else {
            "https://itunes.apple.com/us/rss/toppodcasts/limit=50/genre=$genreId/json"
        }
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) return emptyList()
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val feed = root.optJSONObject("feed") ?: return emptyList()
            val out = mutableListOf<String>()
            // Newer chart format: feed.results[].id
            feed.optJSONArray("results")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { out += it }
            }
            // Older chart format: feed.entry[].id.attributes["im:id"] (entry may be one object)
            val entries = feed.optJSONArray("entry") ?: feed.optJSONObject("entry")?.let { org.json.JSONArray().put(it) }
            if (entries != null) {
                for (i in 0 until entries.length()) {
                    entries.optJSONObject(i)?.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id")
                        ?.takeIf { it.isNotBlank() }?.let { out += it }
                }
            }
            return out.distinct()
        } finally {
            conn.disconnect()
        }
    }

    /** Turns Apple ids into shows with their RSS feed links, keeping chart order. */
    private fun lookup(ids: List<String>): List<SearchResult> {
        val byId = HashMap<String, SearchResult>()
        ids.chunked(100).forEach { chunk ->
            val conn = open("https://itunes.apple.com/lookup?entity=podcast&id=" + chunk.joinToString(","))
            try {
                val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("results") ?: return@forEach
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val feed = o.optString("feedUrl")
                    if (feed.isBlank()) continue
                    byId[o.optLong("collectionId").toString()] = SearchResult(
                        title = o.optString("collectionName"),
                        author = o.optString("artistName"),
                        feedUrl = feed,
                        artworkUrl = o.optString("artworkUrl600", o.optString("artworkUrl100")),
                    )
                }
            } finally {
                conn.disconnect()
            }
        }
        return ids.mapNotNull { byId[it] }
    }

    /** Apple's public podcast directory. No account or key needed. */
    fun search(term: String): List<SearchResult> {
        val url = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=30&term=" +
            URLEncoder.encode(term, "UTF-8")
        val conn = open(url)
        try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(text).optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<SearchResult>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val feed = o.optString("feedUrl")
                if (feed.isBlank()) continue
                out += SearchResult(
                    title = o.optString("collectionName", o.optString("trackName")),
                    author = o.optString("artistName"),
                    feedUrl = feed,
                    artworkUrl = o.optString("artworkUrl600", o.optString("artworkUrl100")),
                )
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    fun parse(input: InputStream): ParsedFeed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)

        var title = ""
        var author = ""
        var description = ""
        var artwork = ""
        var category = ""
        val episodes = mutableListOf<ParsedEpisode>()

        var inItem = false
        var inImage = false
        var eTitle = ""
        var eGuid = ""
        var eDesc = ""
        var eSummary = ""
        var eUrl = ""
        var eDuration = 0L
        var ePub = 0L
        var eLink = ""
        var eChapters = ""

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = p.name.lowercase()
                    when {
                        name == "item" || name == "entry" -> {
                            inItem = true
                            eTitle = ""; eGuid = ""; eDesc = ""; eSummary = ""; eUrl = ""; eDuration = 0; ePub = 0; eLink = ""; eChapters = ""
                        }
                        name == "image" && !inItem && p.getAttributeValue(null, "href") == null -> inImage = true
                        name == "itunes:image" -> {
                            val href = p.getAttributeValue(null, "href")
                            if (!inItem && !href.isNullOrBlank()) artwork = href
                        }
                        name == "enclosure" && inItem -> {
                            val type = p.getAttributeValue(null, "type") ?: ""
                            val url = p.getAttributeValue(null, "url") ?: ""
                            if (url.isNotBlank() && (eUrl.isBlank() || type.startsWith("audio"))) eUrl = url
                        }
                        name == "itunes:category" && !inItem -> {
                            if (category.isBlank()) category = p.getAttributeValue(null, "text")?.trim().orEmpty()
                        }
                        name == "podcast:chapters" && inItem -> {
                            eChapters = p.getAttributeValue(null, "url")?.trim() ?: eChapters
                        }
                        name == "link" && inItem && p.getAttributeValue(null, "rel") == "enclosure" -> {
                            eUrl = p.getAttributeValue(null, "href") ?: eUrl
                        }
                        name in leafTags -> {
                            val text = readTextIfLeaf(p)
                                if (inItem) {
                                    when (name) {
                                        "title" -> eTitle = text
                                        "guid", "id" -> eGuid = text
                                        "description", "content:encoded" -> if (text.length > eDesc.length) eDesc = text
                                        "itunes:summary" -> eSummary = text
                                        "itunes:duration" -> eDuration = parseDuration(text)
                                        "pubdate", "published", "updated" -> if (ePub == 0L) ePub = parseDate(text)
                                        "link" -> eLink = text
                                    }
                                } else if (inImage) {
                                    if (name == "url" && artwork.isBlank()) artwork = text
                                } else {
                                    when (name) {
                                        "title" -> if (title.isBlank()) title = text
                                        "itunes:author" -> author = text
                                        "description" -> if (description.isBlank()) description = text
                                        "itunes:summary" -> if (description.isBlank()) description = text
                                    }
                                }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = p.name.lowercase()
                    if ((name == "item" || name == "entry") && inItem) {
                        inItem = false
                        if (eUrl.isNotBlank()) {
                            val guid = eGuid.ifBlank { eUrl.ifBlank { eLink.ifBlank { eTitle } } }
                            episodes += ParsedEpisode(
                                guid = guid,
                                title = eTitle.ifBlank { "Untitled episode" },
                                description = cleanHtml(eDesc.ifBlank { eSummary }),
                                audioUrl = eUrl,
                                durationMs = eDuration,
                                publishedAt = ePub,
                                chaptersUrl = eChapters,
                            )
                        }
                    } else if (name == "image") {
                        inImage = false
                    }
                }
            }
            event = p.next()
        }
        return ParsedFeed(title.ifBlank { "Untitled podcast" }, author, cleanHtml(description), artwork, episodes, category)
    }

    private val leafTags = setOf(
        "title", "guid", "id", "description", "content:encoded", "itunes:summary", "itunes:duration",
        "pubdate", "published", "updated", "link", "url", "itunes:author",
    )

    /** Returns the text inside the current tag and moves to its END_TAG. */
    private fun readTextIfLeaf(p: XmlPullParser): String {
        val depth = p.depth
        val sb = StringBuilder()
        var ev = p.next()
        while (true) {
            when (ev) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> sb.append(p.text ?: "")
                XmlPullParser.END_TAG -> if (p.depth == depth) return sb.toString().trim()
                XmlPullParser.START_TAG -> {
                    // Not a leaf (e.g. <image> with children). Skip the subtree.
                    skipSubtree(p)
                }
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
            ev = p.next()
        }
    }

    private fun skipSubtree(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    fun cleanHtml(s: String): String {
        if (s.isBlank()) return ""
        return Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT).toString()
            .replace('￼', ' ')
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun parseDuration(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0
        val parts = t.split(":").map { it.trim().toDoubleOrNull() ?: return 0 }
        val seconds = when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            else -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        }
        return (seconds * 1000).toLong()
    }

    private val dateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss zzz",
        "dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd",
    )

    fun parseDate(text: String): Long {
        val t = text.trim()
        for (f in dateFormats) {
            try {
                val d = SimpleDateFormat(f, Locale.US).parse(t)
                if (d != null) return d.time
            } catch (e: Exception) {
            }
        }
        return 0
    }

    /** Reads feed URLs out of an OPML export. */
    fun parseOpml(input: InputStream): List<String> {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        val out = LinkedHashSet<String>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name.equals("outline", ignoreCase = true)) {
                val url = p.getAttributeValue(null, "xmlUrl") ?: p.getAttributeValue(null, "xmlurl")
                if (!url.isNullOrBlank()) out += url.trim()
            }
            ev = p.next()
        }
        return out.toList()
    }
}
