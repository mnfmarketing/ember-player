package app.ember.data

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/**
 * Reads chapter markers from M4B / M4A / MP4 audiobooks.
 * Supports Nero chapters (udta/chpl) and QuickTime chapter text tracks (tref/chap).
 */
object Mp4ChapterParser {

    data class RawChapter(val startMs: Long, val title: String)

    /** What Ember reads from a file's index itself: chapters, and the length (from the movie header). */
    data class Info(val chapters: List<RawChapter>, val durationMs: Long)

    /**
     * Leaf boxes bigger than this are left out when the index is read: they're the audio's sample
     * tables, which can run to tens of megabytes in long books and aren't needed for chapters.
     * (Ember used to load the whole index and gave up past 64 MB, which some 1 GB+ books exceed.)
     */
    private const val KEEP_LEAF = 1L shl 20
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "tref", "edts", "dinf")

    /** Random-access bytes: a local file, or a remote file read with HTTP range requests. */
    interface ByteSource {
        val size: Long
        fun read(buf: ByteBuffer, pos: Long)
    }

    private class ChannelSource(private val ch: FileChannel) : ByteSource {
        override val size: Long get() = ch.size()
        override fun read(buf: ByteBuffer, pos: Long) {
            var p = pos
            while (buf.hasRemaining()) {
                val n = ch.read(buf, p)
                if (n <= 0) break
                p += n
            }
        }
    }

    fun parse(context: Context, uri: Uri): List<RawChapter> = info(context, uri).chapters

    fun parse(source: ByteSource): List<RawChapter> = info(source).chapters

    fun info(context: Context, uri: Uri): Info = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { stream ->
                infoOf(ChannelSource(stream.channel))
            }
        } ?: Info(emptyList(), 0)
    } catch (e: Exception) {
        Info(emptyList(), 0)
    }

    fun info(source: ByteSource): Info = try {
        infoOf(source)
    } catch (e: Exception) {
        Info(emptyList(), 0)
    }

    /** The index with the big sample tables left out (each replaced by an empty box of the same type). */
    private fun slim(ch: ByteSource, start: Long, end: Long, out: java.io.ByteArrayOutputStream) {
        var p = start
        while (p + 8 <= end) {
            val h = ByteBuffer.allocate(minOf(16L, end - p).toInt())
            readFully(ch, h, p)
            h.flip()
            if (h.remaining() < 8) break
            var size = h.int.toLong() and 0xffffffffL
            val typeInt = h.int
            var hdr = 8
            if (size == 1L) {
                if (h.remaining() < 8) break
                size = h.long
                hdr = 16
            } else if (size == 0L) {
                size = end - p
            }
            if (size < hdr || p + size > end) break
            val len = size - hdr
            val body: ByteArray = when {
                typeString(typeInt) in CONTAINERS -> java.io.ByteArrayOutputStream().also { slim(ch, p + hdr, p + size, it) }.toByteArray()
                len <= KEEP_LEAF -> ByteBuffer.allocate(len.toInt()).also { readFully(ch, it, p + hdr) }.array()
                else -> ByteArray(0)
            }
            out.write(ByteBuffer.allocate(8).putInt(body.size + 8).putInt(typeInt).array())
            out.write(body)
            p += size
        }
    }

    private fun movieDurationMs(m: ByteBuffer): Long {
        val mvhd = findPath(m, listOf("mvhd")) ?: return 0
        val d = mvhd.duplicate()
        if (d.remaining() < 20) return 0
        val version = d.get().toInt() and 0xff
        d.position(d.position() + 3)
        return if (version == 1) {
            if (d.remaining() < 28) return 0
            d.position(d.position() + 16)
            val ts = d.int.toLong() and 0xffffffffL
            val dur = d.long
            if (ts > 0 && dur > 0) dur * 1000 / ts else 0
        } else {
            d.position(d.position() + 8)
            val ts = d.int.toLong() and 0xffffffffL
            val dur = d.int.toLong() and 0xffffffffL
            if (ts > 0 && dur > 0 && dur != 0xffffffffL) dur * 1000 / ts else 0
        }
    }

    private fun infoOf(ch: ByteSource): Info {
        val fileSize = ch.size
        var pos = 0L
        var moov: ByteBuffer? = null
        while (pos + 8 <= fileSize) {
            val header = ByteBuffer.allocate(16)
            header.limit(8)
            readFully(ch, header, pos)
            header.flip()
            if (header.remaining() < 8) break
            var boxSize = header.int.toLong() and 0xffffffffL
            val type = typeString(header.int)
            var headerLen = 8
            if (boxSize == 1L) {
                val ext = ByteBuffer.allocate(8)
                readFully(ch, ext, pos + 8)
                ext.flip()
                if (ext.remaining() < 8) break
                boxSize = ext.long
                headerLen = 16
            } else if (boxSize == 0L) {
                boxSize = fileSize - pos
            }
            if (boxSize < headerLen) break
            if (type == "moov") {
                if (boxSize - headerLen <= 0) return Info(emptyList(), 0)
                val out = java.io.ByteArrayOutputStream()
                slim(ch, pos + headerLen, minOf(pos + boxSize, fileSize), out)
                moov = ByteBuffer.wrap(out.toByteArray())
                break
            }
            pos += boxSize
        }
        val m = moov ?: return Info(emptyList(), 0)
        val durationMs = try {
            movieDurationMs(m)
        } catch (e: Exception) {
            0L
        }
        val nero = findPath(m, listOf("udta", "chpl"))?.let { parseChpl(it) }.orEmpty()
        val qt = try {
            parseQtChapters(m, ch)
        } catch (e: Exception) {
            emptyList()
        }
        val chapters = when {
            qt.size > 1 -> qt
            nero.size > 1 -> nero
            qt.isNotEmpty() -> qt
            else -> nero
        }
        return Info(chapters, durationMs)
    }

    private fun typeString(v: Int): String {
        val bytes = byteArrayOf(
            (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte(),
        )
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun readFully(ch: ByteSource, buf: ByteBuffer, start: Long) {
        ch.read(buf, start)
    }

    private fun boxes(buf: ByteBuffer): List<Pair<String, ByteBuffer>> {
        val out = mutableListOf<Pair<String, ByteBuffer>>()
        val b = buf.duplicate()
        while (b.remaining() >= 8) {
            val start = b.position()
            var size = b.int.toLong() and 0xffffffffL
            val type = typeString(b.int)
            var hdr = 8
            if (size == 1L) {
                if (b.remaining() < 8) break
                size = b.long
                hdr = 16
            } else if (size == 0L) {
                size = (b.limit() - start).toLong()
            }
            if (size < hdr || start + size > b.limit()) break
            val content = b.duplicate()
            content.position(start + hdr)
            content.limit((start + size).toInt())
            out += type to content.slice()
            b.position((start + size).toInt())
        }
        return out
    }

    private fun findPath(buf: ByteBuffer, path: List<String>): ByteBuffer? {
        var current = buf
        for (name in path) {
            current = boxes(current).firstOrNull { it.first == name }?.second ?: return null
        }
        return current
    }

    private fun parseChpl(box: ByteBuffer): List<RawChapter> {
        val d = box.duplicate()
        if (d.remaining() < 5) return emptyList()
        val version = d.get().toInt() and 0xff
        d.position(d.position() + 3)
        if (version != 0) {
            if (d.remaining() < 4) return emptyList()
            d.int
        }
        if (!d.hasRemaining()) return emptyList()
        val count = d.get().toInt() and 0xff
        val out = mutableListOf<RawChapter>()
        for (i in 0 until count) {
            if (d.remaining() < 9) break
            val start = d.long
            val len = d.get().toInt() and 0xff
            if (d.remaining() < len) break
            val bytes = ByteArray(len)
            d.get(bytes)
            val title = String(bytes, Charsets.UTF_8).trim()
            out += RawChapter(start / 10_000, title.ifBlank { "Chapter ${i + 1}" })
        }
        return out
    }

    private fun tkhdTrackId(tkhd: ByteBuffer): Int {
        val d = tkhd.duplicate()
        val version = d.get().toInt() and 0xff
        d.position(d.position() + 3)
        d.position(d.position() + if (version == 1) 16 else 8)
        return d.int
    }

    private fun mdhdTimescale(mdhd: ByteBuffer): Long {
        val d = mdhd.duplicate()
        val version = d.get().toInt() and 0xff
        d.position(d.position() + 3)
        d.position(d.position() + if (version == 1) 16 else 8)
        return d.int.toLong() and 0xffffffffL
    }

    private fun parseQtChapters(moov: ByteBuffer, ch: ByteSource): List<RawChapter> {
        val traks = boxes(moov).filter { it.first == "trak" }.map { it.second }
        var chapTrackId: Int? = null
        val byId = HashMap<Int, ByteBuffer>()
        for (t in traks) {
            val kids = boxes(t)
            val tkhd = kids.firstOrNull { it.first == "tkhd" }?.second ?: continue
            byId[tkhdTrackId(tkhd)] = t
            val tref = kids.firstOrNull { it.first == "tref" }?.second
            if (tref != null && chapTrackId == null) {
                val chap = boxes(tref).firstOrNull { it.first == "chap" }?.second
                if (chap != null && chap.remaining() >= 4) chapTrackId = chap.duplicate().int
            }
        }
        val chapTrak = chapTrackId?.let { byId[it] } ?: return emptyList()
        val mdia = findPath(chapTrak, listOf("mdia")) ?: return emptyList()
        val mdhd = findPath(mdia, listOf("mdhd")) ?: return emptyList()
        val timescale = mdhdTimescale(mdhd)
        if (timescale <= 0) return emptyList()
        val stbl = findPath(mdia, listOf("minf", "stbl")) ?: return emptyList()
        val kids = boxes(stbl).toMap()
        val stts = kids["stts"] ?: return emptyList()
        val stsz = kids["stsz"] ?: return emptyList()
        val stsc = kids["stsc"] ?: return emptyList()
        val chunkOffsets = kids["stco"]?.let { readOffsets(it, false) }
            ?: kids["co64"]?.let { readOffsets(it, true) }
            ?: return emptyList()

        // Sample start times
        val times = ArrayList<Long>()
        val t = stts.duplicate()
        t.int
        val entries = t.int
        var acc = 0L
        loop@ for (i in 0 until entries) {
            if (t.remaining() < 8) break
            val cnt = t.int
            val delta = t.int.toLong() and 0xffffffffL
            for (j in 0 until cnt) {
                times += acc
                acc += delta
                if (times.size > 5000) break@loop
            }
        }

        // Sample sizes
        val z = stsz.duplicate()
        z.int
        val fixed = z.int
        val sampleCount = minOf(z.int, 5000)
        val sizes = IntArray(sampleCount) { if (fixed != 0) fixed else if (z.remaining() >= 4) z.int else 0 }

        // Sample file offsets
        val s = stsc.duplicate()
        s.int
        val stscEntries = s.int
        val firstChunk = IntArray(stscEntries)
        val perChunk = IntArray(stscEntries)
        for (i in 0 until stscEntries) {
            if (s.remaining() < 12) break
            firstChunk[i] = s.int
            perChunk[i] = s.int
            s.int
        }
        val offsets = LongArray(sampleCount)
        var sample = 0
        for (i in 0 until stscEntries) {
            val startChunk = firstChunk[i]
            val endChunk = if (i + 1 < stscEntries) firstChunk[i + 1] - 1 else chunkOffsets.size
            var chunk = startChunk
            while (chunk <= endChunk && chunk - 1 < chunkOffsets.size && sample < sampleCount) {
                var off = chunkOffsets[chunk - 1]
                for (k in 0 until perChunk[i]) {
                    if (sample >= sampleCount) break
                    offsets[sample] = off
                    off += sizes[sample]
                    sample++
                }
                chunk++
            }
        }

        val count = minOf(sampleCount, times.size)
        val out = mutableListOf<RawChapter>()
        for (i in 0 until count) {
            val size = sizes[i]
            var title = ""
            if (size >= 2) {
                val buf = ByteBuffer.allocate(minOf(size, 1024))
                readFully(ch, buf, offsets[i])
                buf.flip()
                if (buf.remaining() >= 2) {
                    val len = buf.short.toInt() and 0xffff
                    val l = minOf(len, buf.remaining())
                    val bytes = ByteArray(l)
                    buf.get(bytes)
                    title = decodeText(bytes)
                }
            }
            out += RawChapter(times[i] * 1000 / timescale, title.ifBlank { "Chapter ${i + 1}" })
        }
        return out
    }

    private fun readOffsets(box: ByteBuffer, wide: Boolean): LongArray {
        val d = box.duplicate()
        d.int
        val n = d.int
        val arr = LongArray(maxOf(n, 0))
        for (i in 0 until n) {
            if (d.remaining() < (if (wide) 8 else 4)) break
            arr[i] = if (wide) d.long else (d.int.toLong() and 0xffffffffL)
        }
        return arr
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, Charset.forName("UTF-16")).trim()
        }
        return String(bytes, Charsets.UTF_8).trim()
    }
}
