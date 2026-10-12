package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class VirtualGamepadUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun offlineDisablesStartAndHeldInput() {
        var submitted = false
        compose.setContent { RemoteTheme { PageContent(Modifier.width(390.dp)) {
            VirtualGamepadPanel(VirtualGamepadState(), false, {submitted=true}, {}, {submitted=true})
        } } }
        compose.onNodeWithText("开始持续手柄").assertIsNotEnabled()
        compose.onNodeWithTag("virtual-button-a").performScrollTo().assertIsNotEnabled()
        assertFalse(submitted)
    }
    @Test fun pointerEdgesAndStickReleaseReturnToNeutral() {
        var state by mutableStateOf(VirtualGamepadState(running=true))
        compose.setContent { RemoteTheme { PageContent(Modifier.width(390.dp)) {
            VirtualGamepadPanel(state, true, {}, {}, {change -> state=state.copy(input=change(state.input))})
        } } }
        val a=compose.onNodeWithTag("virtual-button-a")
        a.performScrollTo().performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(listOf("a"),state.input.buttons) }
        a.performTouchInput { up() }
        compose.runOnIdle { assertTrue(state.input.buttons.isEmpty()) }
        a.performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(listOf("a"),state.input.buttons) }
        a.performTouchInput { moveTo(androidx.compose.ui.geometry.Offset(-10f,center.y)) }
        compose.runOnIdle { assertTrue(state.input.buttons.isEmpty()) }
        a.performTouchInput { up() }
        val stick=compose.onNodeWithTag("virtual-stick-right")
        stick.performScrollTo().performTouchInput { down(centerRight) }
        compose.runOnIdle { assertTrue(state.input.rightX > .8);assertEquals(0.0,state.input.rightY,.01) }
        stick.performTouchInput { up() }
        compose.runOnIdle { assertEquals(VirtualGamepadInput(),state.input) }
    }
}
