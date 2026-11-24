package app.ember.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelerTest {
    @Test fun limiterLeavesNormalSamplesAlone() {
        assertEquals(0.5f, Leveler.limit(0.5f), 0f)
        assertEquals(-0.8f, Leveler.limit(-0.8f), 0f)
    }

    @Test fun limiterNeverClips() {
        for (x in listOf(0.9f, 1.5f, 4f, 40f)) {
            val y = Leveler.limit(x)
            assertTrue("$x -> $y", y < 1f && y > 0.8f)
            assertEquals(-y, Leveler.limit(-x), 1e-6f)
        }
    }
}
