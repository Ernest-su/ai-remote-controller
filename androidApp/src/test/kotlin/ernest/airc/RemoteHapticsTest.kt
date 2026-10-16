package ernest.airc

import androidx.compose.foundation.interaction.*
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
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
        val events = MutableSharedFlow<Interaction>()
        val feedback = Feedback()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observeRemoteKeyHaptics(events, feedback, 500) }
        val press = PressInteraction.Press(Offset.Zero)
        events.emit(press)
        advanceTimeBy(100)
        events.emit(PressInteraction.Release(press))
        advanceTimeBy(1000)
        assertEquals(1, feedback.taps)
        assertTrue(feedback.held.isEmpty())
        job.cancelAndJoin()
    }

    @Test fun longPressVibratesUntilReleaseAndIndependentKeysRemainHeld() = runTest {
        val events = MutableSharedFlow<Interaction>()
        val feedback = Feedback()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observeRemoteKeyHaptics(events, feedback, 500) }
        val first = PressInteraction.Press(Offset.Zero)
        val second = PressInteraction.Press(Offset.Zero)
        events.emit(first)
        events.emit(second)
        advanceTimeBy(501)
        runCurrent()
        assertEquals(setOf(first, second), feedback.held)
        events.emit(PressInteraction.Release(first))
        runCurrent()
        assertEquals(setOf(second), feedback.held)
        events.emit(PressInteraction.Release(second))
        runCurrent()
        assertTrue(feedback.held.isEmpty())
        job.cancelAndJoin()
    }

    @Test fun cancelledGestureDoesNotStartDelayedVibration() = runTest {
        val events = MutableSharedFlow<Interaction>()
        val feedback = Feedback()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observeRemoteKeyHaptics(events, feedback, 500) }
        val press = PressInteraction.Press(Offset.Zero)
        events.emit(press)
        events.emit(PressInteraction.Cancel(press))
        advanceTimeBy(1000)
        assertTrue(feedback.held.isEmpty())
        job.cancelAndJoin()
    }

    @Test fun leavingOrDisablingControlsReleasesAllHolds() = runTest {
        val events = MutableSharedFlow<Interaction>()
        val feedback = Feedback()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { observeRemoteKeyHaptics(events, feedback, 500) }
        events.emit(PressInteraction.Press(Offset.Zero))
        advanceTimeBy(501)
        runCurrent()
        assertFalse(feedback.held.isEmpty())
        job.cancelAndJoin()
        assertTrue(feedback.held.isEmpty())
    }
}
