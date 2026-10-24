package app.ember.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NamesTest {

    @Test fun fileStyleChapterNames() {
        val book = "The Bone Season"
        assertEquals("Chapter 29", Names.chapter("The Bone Season - Track 029", book, 28))
        assertEquals("Chapter 29", Names.chapter("The Bone Season_029", book, 28))
        assertEquals("Chapter 29", Names.chapter("029 - The Bone Season", book, 28))
        assertEquals("Chapter 3", Names.chapter("Track 03", book, 2))
        assertEquals("Chapter 7", Names.chapter("07", book, 6))
        assertEquals("Part 2", Names.chapter("Part 02", book, 1))
        assertEquals("Chapter 5", Names.chapter("The Bone Season", book, 4))
        // The audiobook's long title on the files, short title in the library.
        assertEquals("Chapter 12", Names.chapter("The Bone Season (Unabridged) - 12", "The Bone Season (Unabridged)", 11))
    }

    @Test fun realNamesStay() {
        val book = "The Bone Season"
        assertEquals("Chapter 1: The Pale Dreamer", Names.chapter("Chapter 1: The Pale Dreamer", book, 0))
        assertEquals("Opening Credits", Names.chapter("Opening Credits", book, 0))
        assertEquals("The Ball", Names.chapter("The Ball", "Pride and Prejudice", 3))
        assertEquals("Epilogue", Names.chapter("The Bone Season - Epilogue", book, 40))
        assertEquals("Dune Messiah", Names.chapter("Dune Messiah", "Dune", 0))
    }

    @Test fun shortTitles() {
        assertEquals("The Murder at World's End", Names.shortTitle("The Murder at World's End: A Novel"))
        assertEquals("The Bone Season", Names.shortTitle("The Bone Season (Unabridged)"))
        assertEquals("The Bone Season", Names.shortTitle("The Bone Season (The Bone Season, Book 1)"))
        assertEquals("Fourth Wing", Names.shortTitle("Fourth Wing (The Empyrean, #1)"))
        assertEquals("A Court of Thorns and Roses", Names.shortTitle("A Court of Thorns and Roses"))
        assertEquals("Gone Girl", Names.shortTitle("Gone Girl: A Thriller"))
        assertEquals("Project Hail Mary", Names.shortTitle("Project Hail Mary"))
        assertEquals("Dune", Names.shortTitle("Dune, Book One"))
    }

    @Test fun discFolderNames() {
        val yes = listOf("CD1", "CD 01", "cd02", "Disc 2", "Disk 3", "Part 4", "Pt 5", "Pt. 6", "01", "7", "The Bone Season CD1", "The Bone Season - Disc 2", "Disc 1 of 3")
        val no = listOf("Extras", "Bonus Material", "The Bone Season", "Part One", "Dune", "CD", "Season 2 Specials")
        yes.forEach { org.junit.Assert.assertTrue(it, app.ember.data.LibraryScanner.isDiscName(it)) }
        no.forEach { org.junit.Assert.assertFalse(it, app.ember.data.LibraryScanner.isDiscName(it)) }
    }

    @Test fun mergedNames() {
        fun b(t: String) = app.ember.data.BookEntity(id = t, title = t)
        assertEquals("The Bone Season", app.ember.ui.screens.mergedName(listOf(b("The Bone Season CD1"), b("The Bone Season CD2"))))
        assertEquals("The Bone Season", app.ember.ui.screens.mergedName(listOf(b("The Bone Season - Disc 1"), b("The Bone Season - Disc 2"))))
        assertEquals("Loose", app.ember.ui.screens.mergedName(listOf(b("Loose One"), b("Loose Two"))))
        assertEquals("Dune", app.ember.ui.screens.mergedName(listOf(b("Dune"), b("Emma"))))
    }
}
