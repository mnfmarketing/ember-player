package app.ember.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTimeTest {
    private var id = 0L
    private fun e(kind: Int, at: Long, device: String = "") = ListenEventEntity(id = ++id, bookId = "b", at = at, kind = kind, positionMs = 0, device = device)
    private val min = 60_000L

    @Test fun playToPause() {
        val log = listOf(
            e(ListenEventEntity.PLAY, 0), e(ListenEventEntity.SKIP, 5 * min), e(ListenEventEntity.PAUSE, 30 * min),
            e(ListenEventEntity.PLAY, 60 * min), e(ListenEventEntity.PAUSE, 75 * min),
        )
        assertEquals(45 * min, ListenTime.total(log))
    }

    @Test fun playWithNoPauseIsLeftOut() {
        val log = listOf(e(ListenEventEntity.PLAY, 0), e(ListenEventEntity.PLAY, 90 * min), e(ListenEventEntity.PAUSE, 100 * min))
        assertEquals(10 * min, ListenTime.total(log))
    }

    @Test fun playingNowCounts() {
        val log = listOf(e(ListenEventEntity.PLAY, 0))
        assertEquals(20 * min, ListenTime.total(log, now = 20 * min, playingHere = true))
        assertEquals(0L, ListenTime.total(log, now = 20 * min, playingHere = false))
    }

    @Test fun devicesAddUpSeparately() {
        val log = listOf(
            e(ListenEventEntity.PLAY, 0), e(ListenEventEntity.PLAY, 10 * min, "iPhone"),
            e(ListenEventEntity.PAUSE, 20 * min), e(ListenEventEntity.PAUSE, 40 * min, "iPhone"),
        )
        assertEquals(50 * min, ListenTime.total(log))
    }

    @Test fun inOrderEvenIfStoredOutOfOrder() {
        val log = listOf(e(ListenEventEntity.PAUSE, 30 * min), e(ListenEventEntity.PLAY, 0))
        assertEquals(30 * min, ListenTime.total(log))
    }
}
