package app.ember.util

/** Tidier names for what's shown on screen. */
object Names {

    private val numbered = Regex(
        "^(?:(track|part|pt|chapter|chap|ch|cd|disc|disk|file|section|kapitel|capitulo|chapitre)\\.?\\s*[-_#:.]?\\s*)?0*(\\d{1,4})$",
        RegexOption.IGNORE_CASE,
    )
    private val numberThenTitle = Regex("^0*(\\d{1,4})\\s*[-_.:)]\\s*(.+)$")
    private val separators = charArrayOf(' ', '-', '–', '—', ':', '_', '.', ',', '|', '/')

    /**
     * A chapter or file name as people would say it. Names that are only the book's title plus a
     * number ("The Bone Season - Track 029", "029 - The Bone Season", "Track 29", "029") become
     * "Chapter 29" ("Part 3" for parts). Real chapter names are left alone.
     */
    fun chapter(raw: String, bookTitle: String, index: Int): String {
        val original = raw.trim()
        var t = original
        val titles = listOf(bookTitle.trim(), shortTitle(bookTitle)).filter { it.length > 2 }.distinct()
        for (b in titles) {
            if (t.startsWith(b, ignoreCase = true)) {
                val after = t.substring(b.length)
                val rest = after.trim(*separators)
                // Only when the title is set apart ("Book - Epilogue", "Book 029"), never "Dune Messiah".
                val setApart = after.trimStart().firstOrNull()?.let { it in "-–—:_|/.(" || it.isDigit() } ?: true
                if (rest.isBlank() || numbered.matches(rest) || setApart) {
                    t = rest
                    break
                }
            }
        }
        if (t.isBlank()) return "Chapter ${index + 1}"
        numberThenTitle.find(t)?.let { m ->
            val rest = m.groupValues[2].trim(*separators)
            if (titles.any { rest.equals(it, ignoreCase = true) }) return "Chapter ${m.groupValues[1].toInt()}"
        }
        numbered.find(t)?.let { m ->
            val n = m.groupValues[2].toInt()
            val word = m.groupValues[1].lowercase()
            return when (word) {
                "part", "pt" -> "Part $n"
                "cd", "disc", "disk" -> "Disc $n"
                else -> "Chapter $n"
            }
        }
        // Only the book's title came off: what's left is a real name.
        return if (t != original) t else original
    }

    private val noise = listOf(
        // "(Unabridged)", "[Audiobook]"
        Regex("\\s*[\\(\\[]\\s*(un)?abridged(\\s+edition)?\\s*[\\)\\]]", RegexOption.IGNORE_CASE),
        Regex("\\s*[\\(\\[]\\s*audio\\s*book\\s*[\\)\\]]", RegexOption.IGNORE_CASE),
        // "(The Bone Season, Book 1)", "(Book 2)", "(Series #3)"
        Regex("\\s*[\\(\\[][^\\)\\]]*(book|bk\\.?|volume|vol\\.?|#)\\s*\\d+[^\\)\\]]*[\\)\\]]", RegexOption.IGNORE_CASE),
        // ", Book 1" / ": Book One" at the end
        Regex("\\s*[,:]\\s*(book|volume|vol\\.?)\\s+(\\d+|one|two|three|four|five|six|seven|eight|nine|ten)\\s*$", RegexOption.IGNORE_CASE),
        // ": A Novel", " - A Thriller", ": A Fantasy Novel"
        Regex(
            "\\s*[:\\-–—]\\s*an?\\s+([a-z]+\\s+){0,2}(novel|novella|thriller|memoir|mystery|romance|romantasy|story|tale|fantasy)\\s*$",
            RegexOption.IGNORE_CASE,
        ),
    )

    /** A book title without the filler: ": A Novel", "(Unabridged)", "(Series, Book 1)". The book page keeps the full one. */
    fun shortTitle(title: String): String {
        var t = title.trim()
        for (r in noise) t = t.replace(r, "")
        t = t.trim().trimEnd(':', '-', '–', '—', ',').trim()
        return t.ifBlank { title.trim() }
    }
}
