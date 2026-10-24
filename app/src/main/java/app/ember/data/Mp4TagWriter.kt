package app.ember.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Writes title/author/narrator/description tags and chapters into M4B / M4A files.
 *
 * Safety design:
 *  - The audio data is never moved or rewritten. Only the file's index ("moov" box) changes.
 *  - If the index sits at the end of the file it is rewritten in place; otherwise a new index
 *    is appended and the old one is retired (renamed to "free"), so no audio offsets shift.
 *  - Before the first edit, enough is saved to restore the original file exactly.
 *  - After every write the file is re-read; if the result doesn't check out, the previous
 *    state is put back automatically.
 */
object Mp4TagWriter {

    data class Tags(
        val title: String,
        val author: String,
        val narrator: String,
        val description: String,
    )

    sealed class Result {
        object Ok : Result()
        data class Failed(val reason: String) : Result()
    }

    private const val MAX_MOOV = 96L * 1024 * 1024

    // ------------------------------------------------------------------ box model

    private class Box(
        val type: String,
        var payload: ByteArray? = null,
        val children: MutableList<Box>? = null,
        /** Version/flags bytes for "full" container boxes like meta. */
        val prefix: ByteArray? = null,
    ) {
        fun child(t: String) = children?.firstOrNull { it.type == t }
        fun childOrAdd(t: String, factory: () -> Box): Box {
            child(t)?.let { return it }
            val b = factory()
            children!!.add(b)
            return b
        }
    }

    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "edts", "dinf", "tref", "ilst")

    private fun typeOf(b: ByteArray, off: Int) = String(b, off, 4, Charsets.ISO_8859_1)
    private fun typeBytes(t: String) = t.toByteArray(Charsets.ISO_8859_1)

    private fun parse(data: ByteArray, start: Int, end: Int): MutableList<Box> {
        val out = mutableListOf<Box>()
        val bb = ByteBuffer.wrap(data)
        var p = start
        while (p + 8 <= end) {
            var size = bb.getInt(p).toLong() and 0xffffffffL
            val type = typeOf(data, p + 4)
            var hdr = 8
            if (size == 1L) {
                size = bb.getLong(p + 8)
                hdr = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            if (size < hdr || p + size > end) throw IllegalStateException("Unreadable box $type")
            val cStart = p + hdr
            val cEnd = (p + size).toInt()
            out += when {
                type == "meta" -> {
                    // ISO meta has 4 version/flag bytes before its children; QuickTime meta doesn't.
                    val isFull = cEnd - cStart >= 12 && bb.getInt(cStart) == 0 && typeOf(data, cStart + 8).all { it.isLetterOrDigit() }
                    if (isFull) {
                        Box(type, children = parse(data, cStart + 4, cEnd), prefix = data.copyOfRange(cStart, cStart + 4))
                    } else {
                        Box(type, children = parse(data, cStart, cEnd))
                    }
                }
                type in containers -> Box(type, children = parse(data, cStart, cEnd))
                else -> Box(type, payload = data.copyOfRange(cStart, cEnd))
            }
            p = cEnd
        }
        return out
    }

    private fun serialize(box: Box, out: ByteArrayOutputStream) {
        val body = ByteArrayOutputStream()
        box.prefix?.let { body.write(it) }
        if (box.children != null) box.children.forEach { serialize(it, body) } else box.payload?.let { body.write(it) }
        val bytes = body.toByteArray()
        val size = bytes.size + 8L
        if (size > 0xffffffffL) {
            out.write(ByteBuffer.allocate(16).putInt(1).put(typeBytes(box.type)).putLong(bytes.size + 16L).array())
        } else {
            out.write(ByteBuffer.allocate(8).putInt(size.toInt()).put(typeBytes(box.type)).array())
        }
        out.write(bytes)
    }

    private fun serialize(box: Box): ByteArray = ByteArrayOutputStream().also { serialize(box, it) }.toByteArray()

    // ------------------------------------------------------------------ edits

    private fun textItem(type: String, value: String): Box {
        val text = value.toByteArray(Charsets.UTF_8)
        val data = ByteBuffer.allocate(16 + text.size)
            .putInt(16 + text.size).put(typeBytes("data"))
            .putInt(1) // UTF-8 text
            .putInt(0) // locale
            .put(text)
            .array()
        return Box(type, payload = data)
    }

    /** Cover art as an iTunes "covr" item (JPEG = 13, PNG = 14). */
    private fun applyCover(moov: Box, image: ByteArray) {
        val ilst = ilstOf(moov)
        ilst.children!!.removeAll { it.type == "covr" }
        val isPng = image.size > 4 && image[0] == 0x89.toByte() && image[1] == 'P'.code.toByte()
        val data = ByteBuffer.allocate(16 + image.size)
            .putInt(16 + image.size).put(typeBytes("data"))
            .putInt(if (isPng) 14 else 13)
            .putInt(0)
            .put(image)
            .array()
        ilst.children.add(Box("covr", payload = data))
    }

    private fun ilstOf(moov: Box): Box {
        val udta = moov.childOrAdd("udta") { Box("udta", children = mutableListOf()) }
        val meta = udta.childOrAdd("meta") {
            val hdlr = ByteBuffer.allocate(25)
                .putInt(0).putInt(0).put(typeBytes("mdir")).put(typeBytes("appl")).putInt(0).putInt(0).put(0)
                .array()
            Box("meta", children = mutableListOf(Box("hdlr", payload = hdlr)), prefix = ByteArray(4))
        }
        return meta.childOrAdd("ilst") { Box("ilst", children = mutableListOf()) }
    }

    private fun applyTags(moov: Box, tags: Tags) {
        val items = ilstOf(moov).children!!
        fun set(type: String, value: String) {
            items.removeAll { it.type == type }
            if (value.isNotBlank()) items += textItem(type, value)
        }
        set("©nam", tags.title)
        set("©alb", tags.title)
        set("©ART", tags.author)
        set("aART", tags.author)
        set("©wrt", tags.narrator)
        set("desc", tags.description.take(255))
        set("ldes", tags.description)
    }

    private fun chplPayload(chapters: List<Pair<Long, String>>): ByteArray {
        val list = chapters.take(255)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1, 0, 0, 0)) // version 1, flags
        out.write(ByteArray(4)) // reserved
        out.write(list.size)
        list.forEach { (start, title) ->
            out.write(ByteBuffer.allocate(8).putLong(start * 10_000).array()) // 100-nanosecond units
            var t = title.toByteArray(Charsets.UTF_8)
            if (t.size > 255) t = t.copyOf(255)
            out.write(t.size)
            out.write(t)
        }
        return out.toByteArray()
    }

    private fun u32(b: ByteArray, off: Int) = ByteBuffer.wrap(b).getInt(off).toLong() and 0xffffffffL

    private class ChapterTrack(val stbl: Box, val mdhd: Box, val timescale: Long)

    private fun findChapterTrack(moov: Box): ChapterTrack? {
        val traks = moov.children!!.filter { it.type == "trak" }
        var chapId: Long? = null
        for (t in traks) {
            val chap = t.child("tref")?.child("chap")?.payload ?: continue
            if (chap.size >= 4) chapId = u32(chap, 0)
        }
        val id = chapId ?: return null
        for (t in traks) {
            val tkhd = t.child("tkhd")?.payload ?: continue
            val ver = tkhd[0].toInt()
            val tid = u32(tkhd, if (ver == 1) 20 else 12)
            if (tid != id) continue
            val mdia = t.child("mdia") ?: return null
            val mdhd = mdia.child("mdhd") ?: return null
            val p = mdhd.payload ?: return null
            val ts = u32(p, if (p[0].toInt() == 1) 20 else 12)
            val stbl = mdia.child("minf")?.child("stbl") ?: return null
            return ChapterTrack(stbl, mdhd, ts)
        }
        return null
    }

    /** Text samples for a QuickTime chapter track: 2-byte length, UTF-8 text, then an "encd" marker. */
    private fun chapterSamples(chapters: List<Pair<Long, String>>): List<ByteArray> = chapters.map { (_, title) ->
        val t = title.toByteArray(Charsets.UTF_8).let { if (it.size > 65000) it.copyOf(65000) else it }
        ByteBuffer.allocate(2 + t.size + 12)
            .putShort(t.size.toShort()).put(t)
            .putInt(12).put(typeBytes("encd")).putInt(0x00000100)
            .array()
    }

    private fun rebuildChapterTrack(ct: ChapterTrack, chapters: List<Pair<Long, String>>, totalMs: Long, samples: List<ByteArray>, dataOffset: Long) {
        val ts = ct.timescale
        val starts = chapters.map { it.first * ts / 1000 }
        val end = totalMs * ts / 1000
        val deltas = starts.mapIndexed { i, s -> ((starts.getOrNull(i + 1) ?: end) - s).coerceAtLeast(1) }
        val kids = ct.stbl.children!!
        kids.removeAll { it.type in setOf("stts", "stsz", "stsc", "stco", "co64", "stss", "sdtp", "ctts", "stz2") }

        val stts = ByteBuffer.allocate(8 + 8 * deltas.size).putInt(0).putInt(deltas.size)
        deltas.forEach { stts.putInt(1).putInt(it.toInt()) }
        kids += Box("stts", payload = stts.array())

        val stsz = ByteBuffer.allocate(12 + 4 * samples.size).putInt(0).putInt(0).putInt(samples.size)
        samples.forEach { stsz.putInt(it.size) }
        kids += Box("stsz", payload = stsz.array())

        kids += Box("stsc", payload = ByteBuffer.allocate(20).putInt(0).putInt(1).putInt(1).putInt(samples.size).putInt(1).array())

        if (dataOffset > 0xffffffffL) {
            kids += Box("co64", payload = ByteBuffer.allocate(16).putInt(0).putInt(1).putLong(dataOffset).array())
        } else {
            kids += Box("stco", payload = ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(dataOffset.toInt()).array())
        }

        // Keep the chapter track's length equal to the chapters' total length.
        val p = ct.mdhd.payload!!
        val v1 = p[0].toInt() == 1
        val bb = ByteBuffer.wrap(p)
        if (v1) bb.putLong(24, deltas.sum()) else bb.putInt(16, deltas.sum().coerceAtMost(0xffffffffL).toInt())
    }

    // ------------------------------------------------------------------ file access

    private class Top(val type: String, val offset: Long, val size: Long)

    private fun topLevel(ch: FileChannel): List<Top> {
        val out = mutableListOf<Top>()
        val size = ch.size()
        var pos = 0L
        val hdr = ByteBuffer.allocate(16)
        while (pos + 8 <= size) {
            hdr.clear()
            ch.read(hdr, pos)
            hdr.flip()
            if (hdr.remaining() < 8) break
            var s = hdr.int.toLong() and 0xffffffffL
            val t = String(ByteArray(4).also { hdr.get(it) }, Charsets.ISO_8859_1)
            if (s == 1L && hdr.remaining() >= 8) s = hdr.long else if (s == 0L) s = size - pos
            if (s < 8) break
            out += Top(t, pos, s)
            pos += s
        }
        return out
    }

    private fun readAt(ch: FileChannel, pos: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        var p = pos
        while (buf.hasRemaining()) {
            val n = ch.read(buf, p)
            if (n <= 0) break
            p += n
        }
        return buf.array()
    }

    private fun writeAt(ch: FileChannel, pos: Long, bytes: ByteArray) {
        val buf = ByteBuffer.wrap(bytes)
        var p = pos
        while (buf.hasRemaining()) p += ch.write(buf, p)
    }

    /** Where the "restore original" information for one file lives. */
    interface BackupStore {
        fun exists(): Boolean
        fun save(info: String, tail: ByteArray?)
        fun info(): String?
        fun tail(): ByteArray?
        fun clear()
    }

    private class FileBackupStore(private val meta: File, private val bin: File) : BackupStore {
        override fun exists() = meta.exists()
        override fun save(info: String, tail: ByteArray?) {
            if (tail != null) bin.writeBytes(tail)
            meta.writeText(info)
        }
        override fun info() = if (meta.exists()) meta.readText() else null
        override fun tail() = if (bin.exists()) bin.readBytes() else null
        override fun clear() {
            meta.delete()
            bin.delete()
        }
    }

    private fun backupFor(context: Context, uri: Uri): BackupStore {
        val key = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "tag-backups").apply { mkdirs() }
        return FileBackupStore(File(dir, "$key.json"), File(dir, "$key.bin"))
    }

    fun hasOriginalBackup(context: Context, uri: Uri): Boolean = backupFor(context, uri).exists()

    // ------------------------------------------------------------------ public API (Android)

    /**
     * Writes [tags] and/or [chapters] (start ms, title) into the file at [uri].
     * [totalMs] is the book's length, used to size the last chapter.
     */
    fun write(context: Context, uri: Uri, tags: Tags?, chapters: List<Pair<Long, String>>?, totalMs: Long, cover: ByteArray? = null): Result {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "rw")
        } catch (e: SecurityException) {
            return Result.Failed("Ember doesn't have permission to change this file.")
        } catch (e: Exception) {
            return Result.Failed("This file can't be changed from here (${e.javaClass.simpleName}).")
        } ?: return Result.Failed("The file couldn't be opened.")
        return try {
            pfd.use {
                writeCore(
                    reader = FileInputStream(pfd.fileDescriptor).channel,
                    writer = FileOutputStream(pfd.fileDescriptor).channel,
                    backup = backupFor(context, uri),
                    tags = tags,
                    chapters = chapters,
                    totalMs = totalMs,
                    cover = cover,
                    verify = { verifyOnDevice(context, uri, tags, chapters, cover != null) },
                )
            }
        } catch (e: Exception) {
            Result.Failed("This file couldn't be changed (${e.javaClass.simpleName}). Nothing was saved to it.")
        }
    }

    /** Puts the file back exactly as it was before Ember's first edit. */
    fun restoreOriginal(context: Context, uri: Uri): Result {
        val backup = backupFor(context, uri)
        if (!backup.exists()) return Result.Failed("There's no saved original for this file.")
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "rw")
        } catch (e: Exception) {
            null
        } ?: return Result.Failed("Ember doesn't have permission to change this file.")
        return pfd.use { restoreCore(FileOutputStream(pfd.fileDescriptor).channel, backup) }
    }

    private fun verifyOnDevice(context: Context, uri: Uri, tags: Tags?, chapters: List<Pair<Long, String>>?, wroteCover: Boolean): Boolean {
        if (chapters != null && !chaptersMatch(Mp4ChapterParser.parse(context, uri), chapters)) return false
        if ((tags != null && tags.title.isNotBlank()) || wroteCover) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                if (tags != null && tags.title.isNotBlank()) {
                    val album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    if (album?.trim() != tags.title.trim() && title?.trim() != tags.title.trim()) return false
                }
                if (wroteCover && r.embeddedPicture == null) return false
                if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION).isNullOrBlank()) return false
            } catch (e: Exception) {
                return false
            } finally {
                try {
                    r.release()
                } catch (e: Exception) {
                }
            }
        }
        return true
    }

    internal fun chaptersMatch(read: List<Mp4ChapterParser.RawChapter>, wanted: List<Pair<Long, String>>): Boolean {
        val expected = if (wanted.size > 255) 255 else wanted.size
        if (read.size < expected) return false
        return read.zip(wanted).all { (r, w) -> r.title.trim() == w.second.trim() && kotlin.math.abs(r.startMs - w.first) <= 50 }
    }

    // ------------------------------------------------------------------ core (plain JVM, unit-tested)

    fun writeCore(
        reader: FileChannel,
        writer: FileChannel,
        backup: BackupStore,
        tags: Tags?,
        chapters: List<Pair<Long, String>>?,
        totalMs: Long,
        cover: ByteArray? = null,
        verify: () -> Boolean,
    ): Result {
        val tops = topLevel(reader)
        val moovTop = tops.firstOrNull { it.type == "moov" } ?: return Result.Failed("This doesn't look like an M4B/M4A file.")
        if (moovTop.size > MAX_MOOV) return Result.Failed("This file's index is unusually large, so Ember left it alone.")
        val after = tops.filter { it.offset > moovTop.offset }
        val tailRewritable = after.all { it.type == "free" || it.type == "skip" }
        val fileSize = reader.size()

        // 1. Build the new index in memory.
        val moovBytes = readAt(reader, moovTop.offset, moovTop.size.toInt())
        val hdr = if ((ByteBuffer.wrap(moovBytes).getInt(0).toLong() and 0xffffffffL) == 1L) 16 else 8
        val moov = try {
            Box("moov", children = parse(moovBytes, hdr, moovBytes.size))
        } catch (e: Exception) {
            return Result.Failed("Ember couldn't read this file's structure.")
        }
        tags?.let { applyTags(moov, it) }
        cover?.let { applyCover(moov, it) }
        val chapterTrack = chapters?.let { findChapterTrack(moov) }
        val samples = if (chapters != null && chapterTrack != null) chapterSamples(chapters) else emptyList()
        if (chapters != null) {
            val udta = moov.childOrAdd("udta") { Box("udta", children = mutableListOf()) }
            udta.children!!.removeAll { it.type == "chpl" }
            udta.children.add(Box("chpl", payload = chplPayload(chapters)))
        }

        val writePos = if (tailRewritable) moovTop.offset else fileSize
        val sampleBytes = samples.fold(ByteArrayOutputStream()) { o, smp -> o.write(smp); o }.toByteArray()
        if (chapterTrack != null && chapters != null) {
            // Layout is [moov][free: chapter text samples]. The samples' file offset depends on
            // moov's size, which depends on whether a 32- or 64-bit offset table is needed.
            var useCo64 = false
            repeat(2) {
                rebuildChapterTrack(chapterTrack, chapters, totalMs, samples, if (useCo64) 0x1_0000_0000L else 0L)
                val off = writePos + serialize(moov).size + 8
                useCo64 = off > 0xffffffffL
                rebuildChapterTrack(chapterTrack, chapters, totalMs, samples, off)
            }
        }
        val newMoov = serialize(moov)
        val freeBox = if (sampleBytes.isNotEmpty()) {
            ByteBuffer.allocate(8 + sampleBytes.size).putInt(8 + sampleBytes.size).put(typeBytes("free")).put(sampleBytes).array()
        } else {
            ByteArray(0)
        }

        // 2. Save what's needed to restore the original, once per file.
        if (!backup.exists()) {
            backup.save(
                JSONObject().put("size", fileSize).put("moovOffset", moovTop.offset).put("tail", tailRewritable).toString(),
                if (tailRewritable) readAt(reader, moovTop.offset, (fileSize - moovTop.offset).toInt()) else null,
            )
        }

        // 3. Write, keeping a way back for this edit.
        val undoTail = if (tailRewritable) readAt(reader, moovTop.offset, (fileSize - moovTop.offset).toInt()) else null
        try {
            if (tailRewritable) {
                writeAt(writer, writePos, newMoov)
                if (freeBox.isNotEmpty()) writeAt(writer, writePos + newMoov.size, freeBox)
                writer.truncate(writePos + newMoov.size + freeBox.size)
            } else {
                // Append the new index labelled "free", then swap labels so the file is valid at every step.
                val staged = newMoov.copyOf().also { System.arraycopy(typeBytes("free"), 0, it, 4, 4) }
                writeAt(writer, writePos, staged)
                if (freeBox.isNotEmpty()) writeAt(writer, writePos + newMoov.size, freeBox)
                writer.force(true)
                writeAt(writer, writePos + 4, typeBytes("moov"))
                writer.force(true)
                writeAt(writer, moovTop.offset + 4, typeBytes("free"))
            }
            writer.force(true)
        } catch (e: Exception) {
            rollback(writer, moovTop, fileSize, undoTail, tailRewritable)
            return Result.Failed("Writing failed, so the file was left as it was.")
        }

        // 4. Check the result by reading it back.
        val ok = try {
            verify()
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            rollback(writer, moovTop, fileSize, undoTail, tailRewritable)
            return Result.Failed("The saved file didn't check out, so Ember put it back the way it was.")
        }
        return Result.Ok
    }

    private fun rollback(writer: FileChannel, moovTop: Top, oldSize: Long, undoTail: ByteArray?, tail: Boolean) {
        try {
            if (tail && undoTail != null) {
                writeAt(writer, moovTop.offset, undoTail)
            } else {
                writeAt(writer, moovTop.offset + 4, typeBytes("moov"))
            }
            writer.truncate(oldSize)
            writer.force(true)
        } catch (e: Exception) {
        }
    }

    fun restoreCore(writer: FileChannel, backup: BackupStore): Result {
        val infoText = backup.info() ?: return Result.Failed("There's no saved original for this file.")
        val info = JSONObject(infoText)
        val size = info.getLong("size")
        val moovOffset = info.getLong("moovOffset")
        try {
            if (info.getBoolean("tail")) {
                writeAt(writer, moovOffset, backup.tail() ?: return Result.Failed("The saved original is missing."))
            } else {
                writeAt(writer, moovOffset + 4, typeBytes("moov"))
            }
            writer.truncate(size)
            writer.force(true)
        } catch (e: Exception) {
            return Result.Failed("Restoring failed: ${e.message}")
        }
        backup.clear()
        return Result.Ok
    }
}
