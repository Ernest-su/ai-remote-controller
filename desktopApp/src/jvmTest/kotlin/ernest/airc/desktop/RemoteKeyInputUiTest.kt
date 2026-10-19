package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class RemoteKeyInputUiTest {
    @get:Rule val compose = createComposeRule()
    private class Sender : RemoteKeySender {
        val events = mutableListOf<String>()
        override fun down(key: String): String { events += "down:$key"; return key }
        override fun up(pressId: String, cancelled: Boolean) { events += "${if (cancelled) "cancel" else "up"}:$pressId" }
        override fun cancelAll() = Unit
    }
    @Test fun touchEdgesCancelOnSlideAndDisableWithoutGeneratingExtraTap() {
        val sender = Sender(); var enabled by mutableStateOf(true); var taps = 0
        compose.setContent { RemoteTheme { CompositionLocalProvider(LocalRemoteKeySender provides sender) {
            Box(Modifier.size(80.dp).testTag("key").remoteKeyInput("menu", enabled) { taps++ }) { Text("菜单") }
        } } }
        val key = compose.onNodeWithTag("key")
        key.performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(listOf("down:menu"), sender.events) }
        key.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf("down:menu", "up:menu"), sender.events); assertEquals(0, taps) }
        key.performTouchInput { down(center); moveTo(Offset(-20f, -20f)); up() }
        compose.runOnIdle { assertEquals("cancel:menu", sender.events.last()) }
        key.performTouchInput { down(center) }
        compose.runOnIdle { enabled = false }
        compose.runOnIdle { assertEquals("cancel:menu", sender.events.last()) }
        key.performTouchInput { up() }
        compose.runOnIdle { enabled = true }
        compose.runOnIdle { assertEquals(6, sender.events.size) }
        key.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, taps); assertEquals(6, sender.events.size) }
    }
    @Test fun keyboardUsesDownAndUpWhileAccessibilityUsesOneTap() {
        val sender = Sender(); var taps = 0
        compose.setContent { RemoteTheme { CompositionLocalProvider(LocalRemoteKeySender provides sender) {
            Box(Modifier.size(80.dp).testTag("key").remoteKeyInput("ok", true) { taps++ }) { Text("OK") }
        } } }
        val key = compose.onNodeWithTag("key")
        key.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key.performKeyInput { keyDown(Key.Enter) }
        compose.runOnIdle { assertEquals(listOf("down:ok"), sender.events) }
        key.performKeyInput { advanceEventTime(800); keyUp(Key.Enter) }
        compose.runOnIdle { assertEquals(listOf("down:ok", "up:ok"), sender.events); assertEquals(0, taps) }
        key.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, taps) }
    }
    @Test fun parentDragCancelsHeldKeyAndConsumedDownCannotStartAnotherKey() {
        val sender = Sender()
        var intercept by mutableStateOf(false)
        compose.setContent { RemoteTheme { CompositionLocalProvider(LocalRemoteKeySender provides sender) {
            Box(Modifier.size(160.dp).pointerInput(intercept) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (intercept) event.changes.forEach { it.consume() }
                    }
                }
            }) {
                Box(Modifier.size(80.dp).testTag("key").remoteKeyInput("ok", true) {}) { Text("OK") }
            }
        } } }
        val key = compose.onNodeWithTag("key")
        key.performTouchInput { down(center) }
        compose.runOnIdle { intercept = true }
        key.performTouchInput { moveBy(Offset(5f, 0f)); up() }
        compose.runOnIdle { assertEquals(listOf("down:ok", "cancel:ok"), sender.events) }
        key.performTouchInput { down(center); up() }
        compose.runOnIdle { assertEquals(2, sender.events.size); intercept = false }
        key.performTouchInput { down(center); up() }
        compose.runOnIdle { assertEquals(listOf("down:ok", "cancel:ok", "down:ok", "up:ok"), sender.events) }
    }

}
