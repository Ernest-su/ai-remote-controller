package ernest.airc

import androidx.compose.foundation.interaction.*
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteHapticsTest {
    private class Feedback : RemoteHaptics {
        var taps = 0
        val held = mutableSetOf<Any>()
        override fun tap() { taps++ }
        override fun startHold(key: Any) { held += key }
        override fun stopHold(key: Any) { held -= key }
    }
    @Test fun shortPressVibratesOnceAndDoesNotStartHold() = runTest {
        val feedback = Feedback()
        val source = RemoteKeyInteractionSource(this, feedback, 500)
        val press = PressInteraction.Press(Offset.Zero)
        source.tryEmit(press)
        assertEquals(1, feedback.taps)
        advanceTimeBy(100)
        source.tryEmit(PressInteraction.Release(press))
        advanceTimeBy(1000)
        assertTrue(feedback.held.isEmpty())
    }
    @Test fun releaseStopsHoldSynchronouslyWithoutAdvancingScheduler() = runTest {
        val feedback = Feedback()
        val source = RemoteKeyInteractionSource(this, feedback, 500)
        val press = PressInteraction.Press(Offset.Zero)
        source.tryEmit(press)
        advanceTimeBy(501)
        assertEquals(setOf(press), feedback.held)
        source.tryEmit(PressInteraction.Release(press))
        assertTrue("Stop must happen inside the release call", feedback.held.isEmpty())
        advanceTimeBy(1000)
        assertTrue(feedback.held.isEmpty())
    }
    @Test fun suspendingReleaseAlsoStopsBeforeReturning() = runTest {
        val feedback = Feedback()
        val source = RemoteKeyInteractionSource(this, feedback, 500)
        val press = PressInteraction.Press(Offset.Zero)
        source.emit(press)
        advanceTimeBy(501)
        assertFalse(feedback.held.isEmpty())
        source.emit(PressInteraction.Release(press))
        assertTrue(feedback.held.isEmpty())
    }
    @Test fun releasingOneKeyDoesNotStopAnotherHeldKey() = runTest {
        val feedback = Feedback()
        val source = RemoteKeyInteractionSource(this, feedback, 500)
        val first = PressInteraction.Press(Offset.Zero)
        val second = PressInteraction.Press(Offset.Zero)
        source.tryEmit(first); source.tryEmit(second)
        advanceTimeBy(501)
        assertEquals(setOf(first, second), feedback.held)
        source.tryEmit(PressInteraction.Release(first))
        assertEquals(setOf(second), feedback.held)
        source.tryEmit(PressInteraction.Cancel(second))
        assertTrue(feedback.held.isEmpty())
    }
    @Test fun releasingAtThresholdDoesNotStartDelayedVibration() = runTest {
        val feedback = Feedback()
        val source = RemoteKeyInteractionSource(this, feedback, 500)
        val press = PressInteraction.Press(Offset.Zero)
        source.tryEmit(press)
        advanceTimeBy(500)
        source.tryEmit(PressInteraction.Cancel(press))
        runCurrent()
        assertTrue(feedback.held.isEmpty())
    }
    @Test fun disposingStopsImmediatelyAndIgnoresLateEvents() = runTest {
        val feedback = Feedback()
        val source = RemoteKeyInteractionSource(this, feedback, 500)
        source.tryEmit(PressInteraction.Press(Offset.Zero))
        advanceTimeBy(501)
        assertFalse(feedback.held.isEmpty())
        source.dispose()
        assertTrue(feedback.held.isEmpty())
        source.tryEmit(PressInteraction.Press(Offset.Zero))
        advanceTimeBy(1000)
        assertEquals(1, feedback.taps)
        assertTrue(feedback.held.isEmpty())
    }
}
