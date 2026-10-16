package ernest.airc

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteHapticPlaybackTest {
    @Test fun shortTapIsStrongerThanHeldPulsesAndReleaseCancelsWithoutWaiting() = runTest {
        val pulses = mutableListOf<Pair<Long, Int>>()
        var cancels = 0
        val player = RemoteHapticPlayback(this, { true }, true, { duration, amplitude -> pulses += duration to amplitude }, { cancels++ })
        player.tap()
        val tap = pulses.single()
        player.startHold("key")
        val hold = pulses.last()
        assertTrue(tap.first >= 30)
        assertTrue(tap.second > hold.second * 3)
        assertTrue(hold.first < 40)
        advanceTimeBy(121)
        assertTrue(pulses.size > 3)
        player.stopHold("key")
        assertEquals(1, cancels)
        val count = pulses.size
        advanceTimeBy(1000)
        assertEquals(count, pulses.size)
    }
    @Test fun releasingOneOfSeveralHeldKeysKeepsOtherKeysActive() = runTest {
        var pulses = 0
        var cancels = 0
        val player = RemoteHapticPlayback(this, { true }, true, { _, _ -> pulses++ }, { cancels++ })
        player.startHold("first"); player.startHold("second")
        assertEquals(1, pulses)
        player.stopHold("first")
        assertEquals(0, cancels)
        advanceTimeBy(41)
        assertEquals(2, pulses)
        player.stopHold("second")
        assertEquals(1, cancels)
        advanceTimeBy(1000)
        assertEquals(2, pulses)
    }
    @Test fun backgroundOrDisableStopsFuturePulsesAndUnavailableHardwareStaysSilent() = runTest {
        var available = true
        var pulses = 0
        var cancels = 0
        val player = RemoteHapticPlayback(this, { available }, true, { _, _ -> pulses++ }, { cancels++ })
        player.startHold("key")
        available = false
        player.stop()
        assertEquals(1, cancels)
        player.tap(); player.startHold("other")
        advanceTimeBy(1000)
        assertEquals(1, pulses)
    }
    @Test fun hardwareWithoutAmplitudeControlUsesShortWeakHoldPulses() = runTest {
        val durations = mutableListOf<Long>()
        val player = RemoteHapticPlayback(this, { true }, false, { duration, _ -> durations += duration }, {})
        player.startHold("key")
        assertTrue(durations.single() <= 12)
        player.stopHold("key")
        advanceTimeBy(1000)
        assertEquals(1, durations.size)
    }
}
