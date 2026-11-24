package app.ember.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterFitTest {
    private val hour = 3_600_000L
    private val cat = BookInfo.CatalogChapters(listOf(0L to "Opening Credits", hour to "Chapter One", 2 * hour to ""), 3 * hour, true)

    @Test fun sameLengthKeepsTimes() {
        val f = BookInfo.fit(cat, 3 * hour + 20_000, emptyList())
        assertTrue(f.exact)
        assertEquals(listOf(0L to "Opening Credits", hour to "Chapter One", 2 * hour to "Chapter 3"), f.chapters)
    }

    @Test fun differentLengthStretches() {
        val f = BookInfo.fit(cat, 6 * hour, emptyList())
        assertFalse(f.exact)
        assertEquals(listOf(0L, 2 * hour, 4 * hour), f.chapters.map { it.first })
    }

    @Test fun namesOnlyWhenCountsMatch() {
        val mine = listOf(0L to "Track 1", 5_000L to "Track 2", 9_000L to "Track 3")
        assertEquals(listOf(0L to "Opening Credits", 5_000L to "Chapter One", 9_000L to "Chapter 3"), BookInfo.fit(cat, 3 * hour, mine).namesOnly)
        assertNull(BookInfo.fit(cat, 3 * hour, mine.take(2)).namesOnly)
    }
}
