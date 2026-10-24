package app.ember.podcasts

import org.json.JSONObject
import java.nio.charset.Charset

/** One chapter found in a podcast episode. */
data class FoundChapter(val startMs: Long, val title: String, val imageUrl: String? = null, val url: String? = null)

/**
 * Reads podcast chapters from the two places shows put them:
 *  - a Podcasting 2.0 chapters file (JSON) linked from the feed, and
 *  - chapter frames (CHAP) inside an MP3's ID3 tag.
 * Pure Kotlin so it can be tested on real files without a phone.
 */
object EpisodeChapters {

    /** Parses a Podcasting 2.0 JSON chapters file. Chapters marked "toc": false are left out. */
    fun parseJson(text: String): List<FoundChapter> {
        val root = JSONObject(text)
        val arr = root.optJSONArray("chapters") ?: return emptyList()
        val out = mutableListOf<FoundChapter>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.has("toc") && !o.optBoolean("toc", true)) continue
            val start = o.optDouble("startTime", Double.NaN)
            if (start.isNaN() || start < 0) continue
            out += FoundChapter(
                startMs = (start * 1000).toLong(),
                title = o.optString("title").trim(),
                imageUrl = o.optString("img").takeIf { it.isNotBlank() },
                url = o.optString("url").takeIf { it.isNotBlank() },
            )
        }
        return tidy(out)
    }

    /** How many bytes from the start of an MP3 hold its ID3 tag (0 if there isn't one). */
    fun id3TagLength(header: ByteArray): Int {
        if (header.size < 10 || header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return 0
        val size = syncsafe(header, 6)
        val footer = if (header[5].toInt() and 0x10 != 0) 10 else 0
        return 10 + size + footer
    }

    /** Reads CHAP frames from an ID3v2.3 or v2.4 tag at the start of [tag]. */
    fun parseId3(tag: ByteArray): List<FoundChapter> {
        if (id3TagLength(tag) == 0) return emptyList()
        val major = tag[3].toInt()
        if (major != 3 && major != 4) return emptyList()
        val flags = tag[5].toInt()
        val end = minOf(tag.size, 10 + syncsafe(tag, 6))
        var pos = 10
        if (flags and 0x40 != 0) {
            // Extended header: skip it.
            val ext = if (major == 4) syncsafe(tag, pos) else int32(tag, pos) + 4
            pos += ext
        }
        val out = mutableListOf<FoundChapter>()
        while (pos + 10 <= end) {
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = if (major == 4) syncsafe(tag, pos + 4) else int32(tag, pos + 4)
            val body = pos + 10
            if (size <= 0 || body + size > end) break
            if (id == "CHAP") chap(tag, body, body + size, major)?.let { out += it }
            pos = body + size
        }
        return tidy(out)
    }

    private fun chap(b: ByteArray, from: Int, to: Int, major: Int): FoundChapter? {
        var p = from
        while (p < to && b[p] != 0.toByte()) p++ // element id
        p++
        if (p + 16 > to) return null
        val startMs = int32(b, p).toLong() and 0xffffffffL
        p += 16
        var title = ""
        var url: String? = null
        while (p + 10 <= to) {
            val id = String(b, p, 4, Charsets.ISO_8859_1)
            val size = if (major == 4) syncsafe(b, p + 4) else int32(b, p + 4)
            val body = p + 10
            if (size <= 0 || body + size > to) break
            when (id) {
                "TIT2" -> title = text(b, body, body + size)
                "WXXX" -> url = userUrl(b, body, body + size)
                "WOAR", "WOAS" -> if (url == null) url = String(b, body, size, Charsets.ISO_8859_1).trim('\u0000', ' ')
            }
            p = body + size
        }
        return FoundChapter(startMs, title.trim(), null, url)
    }

    private fun charset(enc: Int): Charset = when (enc) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun text(b: ByteArray, from: Int, to: Int): String {
        if (to <= from) return ""
        val cs = charset(b[from].toInt())
        return String(b, from + 1, to - from - 1, cs).trimEnd('\u0000').trim()
    }

    /** WXXX: encoding, description (terminated), then a Latin-1 URL. */
    private fun userUrl(b: ByteArray, from: Int, to: Int): String? {
        if (to <= from) return null
        val enc = b[from].toInt()
        var p = from + 1
        if (enc == 1 || enc == 2) {
            while (p + 1 < to && !(b[p] == 0.toByte() && b[p + 1] == 0.toByte())) p += 2
            p += 2
        } else {
            while (p < to && b[p] != 0.toByte()) p++
            p++
        }
        if (p >= to) return null
        return String(b, p, to - p, Charsets.ISO_8859_1).trim('\u0000', ' ').takeIf { it.isNotBlank() }
    }

    /** Sorted, without duplicates, with blank titles numbered. */
    private fun tidy(list: List<FoundChapter>): List<FoundChapter> =
        list.sortedBy { it.startMs }
            .distinctBy { it.startMs }
            .mapIndexed { i, c -> if (c.title.isBlank()) c.copy(title = "Chapter ${i + 1}") else c }

    private fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7f) shl 21) or ((b[at + 1].toInt() and 0x7f) shl 14) or
            ((b[at + 2].toInt() and 0x7f) shl 7) or (b[at + 3].toInt() and 0x7f)

    private fun int32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)
}
