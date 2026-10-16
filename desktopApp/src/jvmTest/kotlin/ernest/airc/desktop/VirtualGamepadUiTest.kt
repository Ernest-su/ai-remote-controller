package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
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
        compose.onNodeWithText("开始手柄").assertIsNotEnabled()
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
    @Test fun rootAndSyntheticModesExposeTheSameSixButtonsAndTwoSticks() {
        var mode by mutableStateOf("uinput")
        compose.setContent { RemoteTheme {
            GamepadPanel(VirtualGamepadState(running = true, mode = mode), true, {}, {}, {})
        } }
        for (value in listOf("uinput", "synthetic")) {
            compose.runOnIdle { mode = value }
            listOf("a", "b", "x", "y", "select", "start").forEach {
                compose.onNodeWithTag("virtual-button-$it").performScrollTo().assertIsEnabled()
            }
            listOf("left", "right").forEach {
                compose.onNodeWithTag("virtual-stick-$it").performScrollTo().assertExists()
            }
            listOf("l1", "r1", "l2", "r2", "thumb_l", "thumb_r", "hat-上", "hat-下", "hat-左", "hat-右").forEach {
                compose.onNodeWithTag("virtual-button-$it").assertDoesNotExist()
            }
            compose.onNodeWithTag("gamepad-advanced").assertDoesNotExist()
        }
    }

    @Test fun shortLandscapeKeepsBothSticksAndSixButtonsFullyVisible() {
        compose.setContent { RemoteTheme { Box(Modifier.requiredSize(640.dp, 240.dp)) {
            GamepadPanel(VirtualGamepadState(), false, {}, {}, {})
        } } }
        (listOf("a", "b", "x", "y", "select", "start").map { "virtual-button-$it" } +
            listOf("virtual-stick-left", "virtual-stick-right")).forEach {
            val node = compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode()
            assertTrue("$it must be entirely visible", node.boundsInRoot.height >= node.size.height - 1)
        }
    }

}
