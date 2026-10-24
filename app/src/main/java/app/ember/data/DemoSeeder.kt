package app.ember.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.ember.container
import app.ember.util.todayEpochDay
import java.io.File

/**
 * Fills the library with sample data for automated screenshot tests.
 * Only runs when the app is launched with the "ember_demo" extra.
 */
object DemoSeeder {

    private data class Demo(val title: String, val author: String, val series: String = "", val index: Float? = null)

    private val demos = listOf(
        Demo("Pride and Prejudice", "Jane Austen"),
        Demo("Frankenstein", "Mary Shelley"),
        Demo("Dracula", "Bram Stoker"),
        Demo("The Time Machine", "H. G. Wells"),
        Demo("Treasure Island", "Robert Louis Stevenson"),
        Demo("Little Women", "Louisa May Alcott", "March Family", 1f),
        Demo("Good Wives", "Louisa May Alcott", "March Family", 2f),
        Demo("The Hound of the Baskervilles", "Arthur Conan Doyle"),
    )

    suspend fun seed(context: Context) {
        val c = context.container
        val dao = c.db.dao()
        if (dao.getBook("demo-0") != null) {
            if (dao.getBook("demo-0")?.pdfUri == null) attachDemoPdf(context)
            return
        }
        val m4a = File(context.getExternalFilesDir(null), "demo.m4a")
        // Without a pushed demo file, make a quiet tone so the demo books really play.
        val file = if (m4a.exists()) m4a else File(context.getExternalFilesDir(null), "demo.wav").also { if (!it.exists()) writeTone(it, 600) }
        val uri = Uri.fromFile(file).toString()
        var duration = 10 * 60_000L
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(file.absolutePath)
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: duration
            r.release()
        } catch (e: Exception) {
        }

        val now = System.currentTimeMillis()
        demos.forEachIndexed { i, d ->
            val id = "demo-$i"
            val chapterCount = 6
            val chLen = duration / chapterCount
            val chapters = (0 until chapterCount).map { n ->
                ChapterEntity(id, n, chapterNames[(n + i) % chapterNames.size], n * chLen, if (n == chapterCount - 1) duration else (n + 1) * chLen)
            }
            val status = when (i) {
                0, 1, 3 -> BookEntity.STATUS_IN_PROGRESS
                4, 5 -> BookEntity.STATUS_FINISHED
                else -> BookEntity.STATUS_NEW
            }
            val position = when (i) {
                0 -> (duration * 0.42).toLong()
                1 -> (duration * 0.18).toLong()
                3 -> (duration * 0.71).toLong()
                4, 5 -> duration
                else -> 0L
            }
            val book = BookEntity(
                id = id,
                title = d.title,
                author = d.author,
                narrator = if (i % 2 == 0) "Ada Quill" else "",
                series = d.series,
                seriesIndex = d.index,
                durationMs = duration,
                positionMs = position,
                status = status,
                rating = if (status == BookEntity.STATUS_FINISHED) 4 + (i % 2) else 0,
                addedAt = now - i * 86_400_000L,
                lastPlayedAt = if (status == BookEntity.STATUS_IN_PROGRESS) now - i * 3_600_000L else 0,
                finishedAt = if (status == BookEntity.STATUS_FINISHED) now - i * 5 * 86_400_000L else 0,
                sourceUri = uri,
                userEdited = true,
            )
            dao.replaceContent(book, listOf(TrackEntity(id, 0, uri, d.title, duration)), chapters)
        }

        dao.upsertBook(
            BookEntity(
                id = "demo-ext",
                title = "Project Hail Mary",
                author = "Andy Weir",
                source = BookEntity.SOURCE_EXTERNAL,
                durationMs = BookEntity.EXTERNAL_SCALE,
                positionMs = 35,
                status = BookEntity.STATUS_IN_PROGRESS,
                externalApp = "AUDIBLE",
                lastPlayedAt = now - 86_400_000L,
                userEdited = true,
            ),
        )

        val today = todayEpochDay()
        listOf(42, 35, 0, 61, 28, 47, 55, 31, 22, 40, 18).forEachIndexed { i, min ->
            if (min > 0) dao.addListening(today - i - 1, min * 60_000L)
        }
        dao.addListening(today, 19 * 60_000L)
        c.settings.setLastBook("demo-0")
        dao.insertBookmark(BookmarkEntity(bookId = "demo-0", positionMs = (duration * 0.3).toLong(), note = "The ballroom scene. Come back to this.", chapterTitle = chapterNames[2]))
        dao.insertClip(
            ClipEntity(
                mediaId = "demo-0",
                title = demos[0].title,
                subtitle = demos[0].author,
                startMs = (duration * 0.40).toLong(),
                endMs = (duration * 0.40).toLong() + 30_000,
                note = "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
                chapterTitle = chapterNames[2],
            ),
        )
        attachDemoPdf(context)
        val shelfId = dao.getShelves().firstOrNull { it.name == "Comfort rereads" }?.id
            ?: dao.insertShelf(ShelfEntity(name = "Comfort rereads"))
        dao.addToShelf(ShelfBookEntity(shelfId, "demo-0"))
        dao.addToShelf(ShelfBookEntity(shelfId, "demo-5"))

        // The books are in: match anything waiting from a restored backup before the slow cover downloads.
        c.backup.applyPending()

        (demos.indices.map { "demo-$it" to demos[it] } + listOf("demo-ext" to Demo("Project Hail Mary", "Andy Weir"))).forEach { (id, d) ->
            val url = runCatching { c.covers.searchOpenLibrary(d.title, d.author).firstOrNull() }.getOrNull() ?: return@forEach
            val saved = runCatching { c.covers.download(id, url) }.getOrNull() ?: return@forEach
            val b = dao.getBook(id) ?: return@forEach
            dao.updateBook(b.copy(coverPath = saved.path, coverColor = saved.color))
        }
        dao.getClips().filter { it.mediaId == "demo-0" }.forEach { k ->
            dao.updateClip(k.copy(coverPath = dao.getBook("demo-0")?.coverPath))
        }
    }

    /** A small companion PDF for the first demo book (a title page, a family tree and a map). */
    private suspend fun attachDemoPdf(context: Context) {
        val f = File(File(context.filesDir, "docs").apply { mkdirs() }, "pride-and-prejudice-companion.pdf")
        runCatching {
            if (!f.exists()) {
                val doc = android.graphics.pdf.PdfDocument()
                val ink = android.graphics.Paint().apply { color = android.graphics.Color.rgb(60, 40, 30); isAntiAlias = true }
                val red = android.graphics.Paint(ink).apply { color = android.graphics.Color.rgb(170, 30, 30) }
                fun page(n: Int, draw: (android.graphics.Canvas) -> Unit) {
                    val p = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, n).create())
                    draw(p.canvas)
                    doc.finishPage(p)
                }
                page(1) { c ->
                    c.drawRect(40f, 40f, 555f, 802f, android.graphics.Paint(red).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f })
                    c.drawText("Pride and Prejudice", 120f, 330f, android.graphics.Paint(ink).apply { textSize = 40f; isFakeBoldText = true })
                    c.drawText("Companion booklet", 200f, 380f, android.graphics.Paint(ink).apply { textSize = 24f })
                    c.drawText("Family tree · Map of the neighbourhood", 150f, 430f, android.graphics.Paint(ink).apply { textSize = 18f })
                }
                page(2) { c ->
                    val t = android.graphics.Paint(ink).apply { textSize = 18f }
                    c.drawText("The Bennet family", 60f, 80f, android.graphics.Paint(ink).apply { textSize = 28f; isFakeBoldText = true })
                    c.drawText("Mr Bennet  —  Mrs Bennet", 180f, 160f, t)
                    c.drawLine(297f, 170f, 297f, 220f, ink)
                    c.drawLine(80f, 220f, 515f, 220f, ink)
                    listOf("Jane", "Elizabeth", "Mary", "Kitty", "Lydia").forEachIndexed { i, n ->
                        val x = 80f + i * 108f
                        c.drawLine(x, 220f, x, 260f, ink)
                        c.drawText(n, x - 20f, 285f, t)
                    }
                }
                page(3) { c ->
                    c.drawText("Map of Hertfordshire and Derbyshire", 60f, 80f, android.graphics.Paint(ink).apply { textSize = 24f; isFakeBoldText = true })
                    val dot = android.graphics.Paint(red)
                    listOf(Triple("Longbourn", 150f, 300f), Triple("Netherfield", 260f, 260f), Triple("Meryton", 200f, 380f), Triple("Pemberley", 420f, 620f))
                        .forEach { (n, x, y) ->
                            c.drawCircle(x, y, 8f, dot)
                            c.drawText(n, x + 14f, y + 6f, android.graphics.Paint(ink).apply { textSize = 18f })
                        }
                    c.drawLine(150f, 300f, 420f, 620f, android.graphics.Paint(ink).apply { strokeWidth = 2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f) })
                }
                f.outputStream().use { doc.writeTo(it) }
                doc.close()
            }
            context.container.db.dao().setPdf("demo-0", Uri.fromFile(f).toString())
        }
    }

    /** A soft 220 Hz tone as an 8 kHz mono WAV, [seconds] long. */
    private fun writeTone(f: File, seconds: Int) {
        val rate = 8000
        val n = rate * seconds
        val out = java.io.BufferedOutputStream(f.outputStream())
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        out.use {
            it.write("RIFF".toByteArray()); it.write(le32(36 + n)); it.write("WAVE".toByteArray())
            it.write("fmt ".toByteArray()); it.write(le32(16)); it.write(le16(1)); it.write(le16(1))
            it.write(le32(rate)); it.write(le32(rate)); it.write(le16(1)); it.write(le16(8))
            it.write("data".toByteArray()); it.write(le32(n))
            val buf = ByteArray(rate)
            for (sec in 0 until seconds) {
                for (i in 0 until rate) {
                    val t = (sec * rate + i).toDouble() / rate
                    buf[i] = (128 + 24 * Math.sin(2 * Math.PI * 220 * t)).toInt().toByte()
                }
                it.write(buf)
            }
        }
    }

    private val chapterNames = listOf(
        "Opening", "A Letter Arrives", "The Ball", "Storm on the Moor", "Confessions", "Homecoming", "The Long Road",
    )
}
