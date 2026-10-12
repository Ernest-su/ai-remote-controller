package ernest.airc

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse

/** Shared controls only; target input and physical controller lifecycle are separate evidence. */
class VirtualGamepadUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun continuousControlsAreDisabledOffline() {
        var submitted=false
        compose.setContent { RemoteTheme { PageContent {
            VirtualGamepadPanel(VirtualGamepadState(),false,{submitted=true},{},{submitted=true})
        } } }
        compose.onNodeWithText("开始持续手柄").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("virtual-button-a").performScrollTo().assertIsNotEnabled()
        assertFalse(submitted)
    }
    @Test fun touchCancellationReleasesHeldButton() {
        var state by mutableStateOf(VirtualGamepadState(running=true))
        compose.setContent { RemoteTheme { PageContent {
            VirtualGamepadPanel(state,true,{},{},{change -> state=state.copy(input=change(state.input))})
        } } }
        val button=compose.onNodeWithTag("virtual-button-a")
        button.performScrollTo().performTouchInput { down(center) }
        compose.runOnIdle { org.junit.Assert.assertEquals(listOf("a"),state.input.buttons) }
        button.performTouchInput { cancel() }
        compose.runOnIdle { org.junit.Assert.assertTrue(state.input.buttons.isEmpty()) }
    }

    @Test fun faceButtonsFormDiamondAndExtendedInputsRemainAvailable() {
        compose.setContent { RemoteTheme { PageContent {
            VirtualGamepadPanel(VirtualGamepadState(), false, {}, {}, {})
        } } }
        compose.onNodeWithTag("gamepad-face-buttons").performScrollTo()
        val y = compose.onNodeWithTag("virtual-button-y").fetchSemanticsNode().boundsInRoot.center
        val a = compose.onNodeWithTag("virtual-button-a").fetchSemanticsNode().boundsInRoot.center
        val x = compose.onNodeWithTag("virtual-button-x").fetchSemanticsNode().boundsInRoot.center
        val b = compose.onNodeWithTag("virtual-button-b").fetchSemanticsNode().boundsInRoot.center
        org.junit.Assert.assertTrue(y.y < x.y && x.y < a.y)
        org.junit.Assert.assertTrue(x.x < y.x && y.x < b.x)
        listOf("start", "select", "l1", "r1").forEach {
            compose.onNodeWithTag("virtual-button-$it").performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithTag("virtual-stick-right").performScrollTo().assertExists()
    }
}
