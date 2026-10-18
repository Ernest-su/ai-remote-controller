package ernest.airc

import kotlin.test.*

class PlaybackTimeTest {
    @Test fun formatsAndParsesTimes() {
        assertEquals("1:02:03", formatPlaybackTime(3723000))
        assertEquals(750000L, parsePlaybackTime("12:30"))
        assertEquals(3723000L, parsePlaybackTime("1:02:03"))
        listOf("-1:00", "1:60", "7000", "1::2", "1:2.0").forEach { assertNull(parsePlaybackTime(it)) }
    }
    @Test fun relativeSeekClampsAtBoundaries() {
        assertEquals(0, relativePlaybackPosition(5000, -10, 60000))
        assertEquals(60000, relativePlaybackPosition(55000, 30, 60000))
    }
}
