package app.ember.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sync rules: newest change wins item by item, deletions travel, nothing is lost by accident. */
class SyncMergeTest {

    private val fold = "fold"
    private val phone = "iphone"
    private val bk = "thelanternthief|adawren"
    private val all = setOf("*", bk)

    private fun pos(p: Long, lp: Long) = Canon.obj("p" to p, "lp" to lp, "d" to 3600)
    private fun e(v: String?, t: Long, d: String = phone, o: String = bk) = SyncEntry(v, t, d, o)
    private fun li(v: String, o: String = bk, hint: Long = 0, hinted: Boolean = false) = LocalItem(v, o, hint, hinted)

    @Test fun canonicalTextIsTheSameWhateverTheOrder() {
        assertEquals("""{"a":1,"b":"x"}""", Canon.of("""{ "b" : "x", "a" : 1 }"""))
        assertEquals(Canon.obj("p" to 5L, "n" to "hi \"there\""), Canon.of("""{"n":"hi \"there\"","p":5}"""))
        assertTrue(Canon.same("""{"v":["a","b"]}""", Canon.obj("v" to listOf("a", "b"))))
        assertFalse(Canon.same("""{"v":1}""", """{"v":2}"""))
        // Swift writes "1.0" for some whole numbers and escapes slashes; still the same value.
        assertTrue(Canon.same("""{"v":1.0,"u":"a\/b"}""", """{"u":"a/b","v":1}"""))
    }

    @Test fun bookKeysIgnoreCaseAccentsAndPunctuation() {
        assertEquals("thelanternthiefofvell|adawren", SyncKeys.book("The Lantern Thief of Vell", "Ada Wren", false))
        assertEquals(SyncKeys.book("Café: Société", "Zoë"), SyncKeys.book("cafe societe", "ZOE"))
        assertEquals("x|saltglass|corinhale", SyncKeys.book("Saltglass", "Corin Hale", true))
        assertNull(SyncKeys.book("  ", "Someone", false))
        assertEquals(SyncKeys.logFile("a|b"), SyncKeys.logFile("a|b"))
        assertTrue(SyncKeys.logFile("a|b").matches(Regex("log-[0-9a-f]{20}\\.json")))
        // The iPhone app computes the same name (SyncModelTests).
        assertEquals("log-88393465741aa111be93.json", SyncKeys.logFile("thelanternthief|adawren"))
    }

    private fun SyncKeys.book(t: String, a: String) = book(t, a, false)

    @Test fun firstSyncUploadsEverything() {
        val local = mapOf("b/$bk/pos" to li(pos(1000, 50), hint = 50, hinted = true), "goal" to li(Canon.obj("d" to 30, "y" to 24), o = "*"))
        val r = SyncMerge.merge(emptyMap(), emptyMap(), local, all, emptySet(), fold, now = 100)
        assertTrue(r.upload)
        assertEquals(setOf("b/$bk/pos", "goal"), r.won)
        assertEquals(50L, r.merged.getValue("b/$bk/pos").t)
        assertEquals(0L, r.merged.getValue("goal").t) // first sync: settings don't claim to be new
        assertTrue(r.apply.isEmpty())
    }

    @Test fun newerPlaceOnTheOtherDeviceComesHere() {
        val shadow = mapOf("b/$bk/pos" to e(pos(1000, 50), 50, fold))
        val remote = mapOf("b/$bk/pos" to e(pos(9000, 80), 80))
        val local = mapOf("b/$bk/pos" to li(pos(1000, 50), hint = 50, hinted = true))
        val r = SyncMerge.merge(remote, shadow, local, all, all, fold, now = 100)
        assertFalse(r.upload)
        assertEquals(pos(9000, 80), r.apply.getValue("b/$bk/pos").v)
    }

    @Test fun listeningHereLaterBeatsTheOtherDevice() {
        val shadow = mapOf("b/$bk/pos" to e(pos(1000, 50), 50, fold))
        val remote = mapOf("b/$bk/pos" to e(pos(9000, 80), 80))
        val local = mapOf("b/$bk/pos" to li(pos(2000, 90), hint = 90, hinted = true))
        val r = SyncMerge.merge(remote, shadow, local, all, all, fold, now = 100)
        assertTrue(r.upload)
        assertTrue(r.apply.isEmpty())
        assertEquals(pos(2000, 90), r.merged.getValue("b/$bk/pos").v)
    }

    @Test fun listeningHereEarlierLosesEvenIfSyncedLater() {
        // Both devices listened offline; the iPhone listened more recently.
        val shadow = mapOf("b/$bk/pos" to e(pos(1000, 50), 50, fold))
        val remote = mapOf("b/$bk/pos" to e(pos(9000, 80), 80))
        val local = mapOf("b/$bk/pos" to li(pos(2000, 70), hint = 70, hinted = true))
        val r = SyncMerge.merge(remote, shadow, local, all, all, fold, now = 1000)
        assertEquals(pos(9000, 80), r.merged.getValue("b/$bk/pos").v)
        assertEquals(pos(9000, 80), r.apply.getValue("b/$bk/pos").v)
    }

    @Test fun markingFinishedMovesThePlaceToo() {
        val st = { s: Int -> Canon.obj("s" to s, "f" to 0L) }
        val shadow = mapOf("b/$bk/pos" to e(pos(9000, 80), 80), "b/$bk/status" to e(st(1), 40))
        val remote = shadow
        // Marked finished by hand: place goes to the end but "last played" didn't change.
        val local = mapOf("b/$bk/pos" to li(pos(3600_000, 80), hint = 80, hinted = true), "b/$bk/status" to li(st(2)))
        val r = SyncMerge.merge(remote, shadow, local, all, all, fold, now = 500)
        assertEquals(pos(3600_000, 80), r.merged.getValue("b/$bk/pos").v)
        assertEquals(st(2), r.merged.getValue("b/$bk/status").v)
        assertTrue(r.apply.isEmpty())
    }

    @Test fun aBookThatJustAppearedTakesTheOtherDevicesPlace() {
        // The iPhone has progress; the book has only just been scanned here (never played).
        val remote = mapOf(
            "b/$bk/pos" to e(pos(9000, 80), 80),
            "b/$bk/status" to e(Canon.obj("s" to 1, "f" to 0L), 60),
            "b/$bk/rating" to e(Canon.obj("v" to 4), 70),
        )
        val shadow = remote // seen at the last sync, but the book wasn't here then
        val local = mapOf(
            "b/$bk/pos" to li(pos(0, 0), hint = 0, hinted = true),
            "b/$bk/status" to li(Canon.obj("s" to 0, "f" to 0L)),
            "b/$bk/rating" to li(Canon.obj("v" to 0)),
        )
        val r = SyncMerge.merge(remote, shadow, local, all, setOf("*"), fold, now = 500)
        assertFalse(r.upload)
        assertEquals(3, r.apply.size)
    }

    @Test fun bookmarksFromBothDevicesAreKept() {
        val m1 = Canon.obj("b" to bk, "p" to 100L, "n" to "", "c" to "")
        val m2 = Canon.obj("b" to bk, "p" to 200L, "n" to "", "c" to "")
        val remote = mapOf("mark/1" to e(m1, 10))
        val shadow = emptyMap<String, SyncEntry>()
        val local = mapOf("mark/2" to li(m2))
        val r = SyncMerge.merge(remote, shadow, local, all, all, fold, now = 50)
        assertEquals(setOf("mark/1", "mark/2"), r.merged.keys)
        assertEquals(m1, r.apply.getValue("mark/1").v)
        assertTrue(r.upload)
    }

    @Test fun deletingABookmarkHereDeletesItEverywhere() {
        val m1 = Canon.obj("b" to bk, "p" to 100L, "n" to "", "c" to "")
        val remote = mapOf("mark/1" to e(m1, 10))
        val r = SyncMerge.merge(remote, remote, emptyMap(), all, all, fold, now = 50)
        val gone = r.merged.getValue("mark/1")
        assertNull(gone.v)
        assertEquals(50L, gone.t)
        assertTrue(r.upload)
    }

    @Test fun aBookmarkDeletedElsewhereIsDeletedHere() {
        val m1 = Canon.obj("b" to bk, "p" to 100L, "n" to "", "c" to "")
        val shadow = mapOf("mark/1" to e(m1, 10))
        val remote = mapOf("mark/1" to e(null, 40))
        val r = SyncMerge.merge(remote, shadow, mapOf("mark/1" to li(m1)), all, all, fold, now = 50)
        assertTrue(r.apply.containsKey("mark/1"))
        assertNull(r.apply.getValue("mark/1").v)
    }

    @Test fun bookmarksOfABookNotHereAreNeverDeleted() {
        val m1 = Canon.obj("b" to bk, "p" to 100L, "n" to "", "c" to "")
        val remote = mapOf("mark/1" to e(m1, 10))
        // The book isn't in this library: its bookmarks aren't "missing", just waiting.
        val r = SyncMerge.merge(remote, remote, emptyMap(), setOf("*"), setOf("*"), fold, now = 50)
        assertEquals(m1, r.merged.getValue("mark/1").v)
        assertFalse(r.upload)
        // Not even when the book was here at the last sync but has been removed since.
        val r2 = SyncMerge.merge(remote, remote, emptyMap(), setOf("*"), all, fold, now = 50)
        assertEquals(m1, r2.merged.getValue("mark/1").v)
    }

    @Test fun placesAreNeverDeleted() {
        val remote = mapOf("b/$bk/pos" to e(pos(9000, 80), 80))
        val r = SyncMerge.merge(remote, remote, emptyMap(), all, all, fold, now = 500)
        assertEquals(pos(9000, 80), r.merged.getValue("b/$bk/pos").v)
    }

    @Test fun anUnansweredQuestionDoesNotOverwriteTheOtherDevice() {
        // Ember asked "Go there?" and you haven't answered: the local place is frozen.
        val shadow = mapOf("b/$bk/pos" to e(pos(9000, 80), 80))
        val local = mapOf("b/$bk/pos" to li(pos(1000, 95), hint = 95, hinted = true))
        val r = SyncMerge.merge(shadow, shadow, local, all, all, fold, now = 500, frozen = setOf("b/$bk/pos"))
        assertFalse(r.upload)
        assertEquals(pos(9000, 80), r.merged.getValue("b/$bk/pos").v)
    }

    @Test fun oldDeletionsAreForgotten() {
        val old = 1_000L
        val now = old + (SyncMerge.TOMBSTONE_DAYS + 1) * 86_400_000L
        val remote = mapOf("mark/1" to e(null, old))
        val r = SyncMerge.merge(remote, remote, emptyMap(), all, all, fold, now = now)
        assertFalse(r.merged.containsKey("mark/1"))
    }

    @Test fun sameValueDoesNotBounceBackAndForth() {
        val v = Canon.obj("v" to 4)
        val remote = mapOf("b/$bk/rating" to e(v, 70))
        val local = mapOf("b/$bk/rating" to li(v))
        val r = SyncMerge.merge(remote, emptyMap(), local, all, all, fold, now = 500)
        assertFalse(r.upload)
        assertTrue(r.apply.isEmpty())
    }

    @Test fun stateFileRoundTrips() {
        val s = SyncState()
        s.items["mark/1"] = SyncEntry(Canon.obj("p" to 1L), 5, phone, bk)
        s.items["mark/2"] = SyncEntry(null, 6, fold, bk)
        s.devices[phone] = "iPhone" to 9L
        s.owners += bk
        val back = SyncState.parse(s.toJson())
        assertEquals(s.items, back.items)
        assertEquals(s.devices, back.devices)
        assertEquals(s.owners, back.owners)
    }

    private fun le(u: String, at: Long, to: Long = -1) = LogEntry(u, u.substringBefore(':'), at, 1, at * 10, to, 100, 0)

    @Test fun logsFromBothDevicesInterleave() {
        val (list, cleared) = LogMerge.merge(listOf(le("iphone:1", 10), le("iphone:2", 30)), 0, listOf(le("fold:1", 20), le("fold:2", 40)), 0)
        assertEquals(listOf("fold:2", "iphone:2", "fold:1", "iphone:1"), list.map { it.u })
        assertEquals(0L, cleared)
    }

    @Test fun clearAllOnEitherDeviceClearsBoth() {
        val (list, cleared) = LogMerge.merge(listOf(le("iphone:1", 10), le("iphone:2", 30)), 25, listOf(le("fold:1", 20), le("fold:2", 40)), 0)
        assertEquals(listOf("fold:2", "iphone:2"), list.map { it.u })
        assertEquals(25L, cleared)
    }

    @Test fun logKeepsTheNewest400() {
        val local = (1..300L).map { le("fold:$it", it) }
        val remote = (1..300L).map { le("iphone:$it", it * 2) }
        val (list, _) = LogMerge.merge(remote, 0, local, 0)
        assertEquals(400, list.size)
        assertEquals("iphone:300", list.first().u)
    }

    @Test fun aMergedSkipKeepsItsLatestVersion() {
        val (list, _) = LogMerge.merge(listOf(le("fold:1", 10, to = 100)), 0, listOf(le("fold:1", 12, to = 160)), 0)
        assertEquals(1, list.size)
        assertEquals(160L, list[0].to)
    }

    @Test fun logFileRoundTrips() {
        val f = LogFile(bk, 7, listOf(le("fold:1", 10, 99)))
        val back = LogFile.parse(f.toJson())
        assertNotNull(back)
        assertEquals(bk, back!!.bookKey)
        assertEquals(7L, back.cleared)
        assertEquals(f.entries, back.entries)
    }
}
