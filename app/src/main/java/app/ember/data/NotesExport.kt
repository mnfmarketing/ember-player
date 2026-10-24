package app.ember.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A book's notes as Markdown (Notion, Obsidian and most notes apps import it). */
object NotesExport {

    /** The book's details, your rating, bookmarks and clips, with chapters and times. */
    suspend fun markdown(dao: LibraryDao, bookId: String): String = withContext(Dispatchers.IO) {
        val b = dao.getBook(bookId) ?: return@withContext ""
        val marks = dao.getBookmarks(bookId).sortedBy { it.positionMs }
        val clips = dao.getClips().filter { it.mediaId == bookId }.sortedBy { it.startMs }
        val chapters = dao.getChapters(bookId)
        val day = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
        fun clock(ms: Long) = app.ember.util.formatClock(ms)
        fun chapterAt(ms: Long, fallback: String) = fallback.ifBlank { chapters.lastOrNull { it.startMs <= ms }?.title ?: "" }
        buildString {
            append("# ").append(b.title).append("\n\n")
            val facts = mutableListOf<String>()
            if (b.author.isNotBlank()) facts += "**Author:** ${b.author}"
            if (b.narrator.isNotBlank()) facts += "**Narrated by:** ${b.narrator}"
            if (b.series.isNotBlank()) facts += "**Series:** ${b.series}" + (b.seriesIndex?.let { i -> " #" + (if (i % 1f == 0f) i.toInt().toString() else i.toString()) } ?: "")
            if (b.rating > 0) facts += "**My rating:** " + "★".repeat(b.rating) + "☆".repeat(5 - b.rating) + " (${b.rating}/5)"
            facts += "**Status:** " + when (b.status) {
                BookEntity.STATUS_FINISHED -> if (b.finishedAt > 0) "Finished ${day.format(java.util.Date(b.finishedAt))}" else "Finished"
                BookEntity.STATUS_IN_PROGRESS -> "Listening (${(b.progress * 100).toInt()}%)"
                else -> "Not started"
            }
            if (b.durationMs > 0 && !b.isExternal) facts += "**Length:** ${app.ember.util.formatDuration(b.durationMs)}"
            facts.forEach { append(it).append("  \n") }
            if (b.description.isNotBlank()) append("\n> ").append(b.description.trim().replace("\n", "\n> ")).append("\n")
            append("\n## Bookmarks\n\n")
            if (marks.isEmpty()) append("_No bookmarks._\n")
            marks.forEach { m ->
                val ch = chapterAt(m.positionMs, m.chapterTitle)
                append("- **").append(clock(m.positionMs)).append("**")
                if (ch.isNotBlank()) append(" · ").append(ch)
                if (m.note.isNotBlank()) append("  \n  ").append(m.note.trim().replace("\n", "  \n  "))
                append("\n")
            }
            append("\n## Clips\n\n")
            if (clips.isEmpty()) append("_No clips._\n")
            clips.forEach { cl ->
                val ch = chapterAt(cl.startMs, cl.chapterTitle)
                append("- **").append(clock(cl.startMs)).append("–").append(clock(cl.endMs)).append("**")
                if (ch.isNotBlank()) append(" · ").append(ch)
                if (cl.note.isNotBlank()) append("  \n  ").append(cl.note.trim().replace("\n", "  \n  "))
                append("\n")
            }
            append("\n---\n_Exported from Ember on ").append(day.format(java.util.Date())).append("_\n")
        }
    }

}
