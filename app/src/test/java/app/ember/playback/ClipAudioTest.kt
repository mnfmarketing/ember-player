package app.ember.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipAudioTest {
    @Test fun inOneFile() {
        assertEquals(listOf(Triple(1, 5_000L, 35_000L)), ClipAudio.spans(listOf(60_000, 60_000, 60_000), 65_000, 95_000))
    }

    @Test fun acrossTwoFiles() {
        assertEquals(
            listOf(Triple(0, 50_000L, 60_000L), Triple(1, 0L, 20_000L)),
            ClipAudio.spans(listOf(60_000, 60_000), 50_000, 80_000),
        )
    }

    @Test fun singleFileBook() {
        assertEquals(listOf(Triple(0, 3_600_000L, 3_630_000L)), ClipAudio.spans(listOf(36_000_000), 3_600_000, 3_630_000))
    }

    @Test fun unknownLengthTakesTheRest() {
        assertEquals(listOf(Triple(0, 10_000L, 40_000L)), ClipAudio.spans(listOf(0), 10_000, 40_000))
    }

    @Test fun noFiles() {
        assertNull(ClipAudio.spans(emptyList(), 0, 1_000))
    }
}
