package ernest.airc

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Local shared controls; this fixture does not claim target game input. */
class VirtualGamepadUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun continuousControlsAreDisabledOffline() {
        var submitted=false
        compose.setContent { RemoteTheme { Column(Modifier.fillMaxSize()) {
            GamepadSessionButton(VirtualGamepadState(),false,{submitted=true},{submitted=true})
            Box(Modifier.weight(1f)) { GamepadPanel(VirtualGamepadState(),false) {submitted=true} }
        } } }
        compose.onNodeWithContentDescription("开始手柄").assertIsNotEnabled()
        compose.onNodeWithTag("virtual-button-a").assertIsNotEnabled()
        assertFalse(submitted)
    }
    @Test fun touchCancellationReleasesHeldButton() {
        var state by mutableStateOf(VirtualGamepadState(running=true))
        compose.setContent { RemoteTheme {
            GamepadPanel(state,true) {change -> state=state.copy(input=change(state.input))}
        } }
        val button=compose.onNodeWithTag("virtual-button-a")
        button.performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(listOf("a"),state.input.buttons) }
        button.performTouchInput { cancel() }
        compose.runOnIdle { assertTrue(state.input.buttons.isEmpty()) }
    }
    @Test fun faceButtonsFormDiamondAndOnlyRequestedControlsRemain() {
        compose.setContent { RemoteTheme { GamepadPanel(VirtualGamepadState(), false) {} } }
        val y = compose.onNodeWithTag("virtual-button-y").fetchSemanticsNode().boundsInRoot.center
        val a = compose.onNodeWithTag("virtual-button-a").fetchSemanticsNode().boundsInRoot.center
        val x = compose.onNodeWithTag("virtual-button-x").fetchSemanticsNode().boundsInRoot.center
        val b = compose.onNodeWithTag("virtual-button-b").fetchSemanticsNode().boundsInRoot.center
        assertTrue(y.y < x.y && x.y < a.y)
        assertTrue(x.x < y.x && y.x < b.x)
        listOf("start", "select").forEach {
            compose.onNodeWithTag("virtual-button-$it").assertIsDisplayed().assertIsNotEnabled()
        }
        compose.onNodeWithTag("virtual-stick-right").assertIsDisplayed()
        listOf("l1", "r1", "l2", "r2", "thumb_l", "thumb_r", "hat-上", "hat-下", "hat-左", "hat-右").forEach {
            compose.onNodeWithTag("virtual-button-$it").assertDoesNotExist()
        }
    }
    @Test fun multitouchAndSessionChangesNeverMoveTheViewport() {
        var state by mutableStateOf(VirtualGamepadState())
        compose.setContent { RemoteTheme { Column(Modifier.fillMaxSize()) {
            Row { Text("游戏手柄"); GamepadSessionButton(state,true,
                {state=VirtualGamepadState(running=true)}, {state=VirtualGamepadState()}) }
            Box(Modifier.weight(1f)) { GamepadPanel(state,true) {change -> state=state.copy(input=change(state.input))} }
        } } }
        val tags=listOf("virtual-stick-left", "virtual-stick-right", "virtual-button-a", "virtual-button-start", "virtual-button-select")
        val before=tags.associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        compose.onNodeWithContentDescription("开始手柄").performClick()
        val page=compose.onNodeWithTag("gamepad-page")
        val origin=page.fetchSemanticsNode().boundsInRoot.topLeft
        val stick=before.getValue("virtual-stick-left").center-origin
        val a=before.getValue("virtual-button-a").center-origin
        page.performTouchInput { down(0,stick); down(1,a); moveTo(0,stick+Offset(30f,20f)) }
        compose.runOnIdle { assertEquals(listOf("a"),state.input.buttons); assertTrue(state.input.leftX>0) }
        page.performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(VirtualGamepadInput(),state.input) }
        page.performTouchInput { swipe(Offset(4f,4f), Offset(width-4f,height-4f)) }
        compose.onNodeWithContentDescription("停止手柄").performClick()
        for(value in listOf(VirtualGamepadState(starting=true), VirtualGamepadState(stopping=true), VirtualGamepadState(running=true,reconnecting=true), VirtualGamepadState(error="fixture"))) {
            compose.runOnIdle { state=value }
            before.forEach { (tag,rect) -> assertEquals(rect,compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot) }
            if(value.reconnecting) {
                compose.onNodeWithTag("virtual-button-a").assertIsNotEnabled()
                compose.onNodeWithContentDescription("正在重连手柄，点击停止").assertIsEnabled().performClick()
                compose.runOnIdle { assertFalse(state.running) }
            }
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(0)
        compose.onNodeWithText("全部释放并回中").assertDoesNotExist()
    }
}
