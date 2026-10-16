package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteHapticsUiTest {
    @get:Rule val compose = createComposeRule()
    private class Feedback : RemoteHaptics {
        var taps = 0
        val held = mutableSetOf<Any>()
        override fun tap() { taps++ }
        override fun startHold(key: Any) { held += key }
        override fun stopHold(key: Any) { held -= key }
    }
    @Test fun enabledKeyVibratesAndSendsOneActionWhileOfflineKeyDoesNeither() {
        val feedback = Feedback()
        var connected by mutableStateOf(true)
        var calls = 0
        compose.setContent { RemoteTheme { CompositionLocalProvider(LocalRemoteHaptics provides feedback) {
            KeypadPanel(connected, "", { calls++ })
        } } }
        compose.onNodeWithTag("keypad-1").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, feedback.taps); assertEquals(1, calls); connected = false }
        compose.onNodeWithTag("keypad-1").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, feedback.taps); assertEquals(1, calls); assertTrue(feedback.held.isEmpty()) }
    }
    @Test fun realButtonReleaseAndCancellationStopHeldFeedback() {
        val feedback = Feedback()
        compose.setContent { RemoteTheme { CompositionLocalProvider(LocalRemoteHaptics provides feedback) {
            KeypadPanel(true, "", {})
        } } }
        val key = compose.onNodeWithTag("keypad-1")
        key.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(1, feedback.held.size) }
        key.performTouchInput { up() }
        compose.runOnIdle { assertTrue(feedback.held.isEmpty()) }
        key.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(1, feedback.held.size) }
        key.performTouchInput { moveTo(androidx.compose.ui.geometry.Offset(-100f, -100f)) }
        compose.runOnIdle { assertTrue(feedback.held.isEmpty()) }
        key.performTouchInput { up() }
    }

}
