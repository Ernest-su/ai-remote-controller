package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
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
        compose.setContent { RemoteTheme { Column(Modifier.requiredSize(390.dp, 640.dp)) {
            GamepadSessionButton(VirtualGamepadState(), false, {submitted=true}, {submitted=true})
            Box(Modifier.weight(1f)) { GamepadPanel(VirtualGamepadState(), false) {submitted=true} }
        } } }
        compose.onNodeWithContentDescription("开始手柄").assertIsNotEnabled()
        compose.onNodeWithTag("virtual-button-a").assertIsNotEnabled()
        assertFalse(submitted)
    }
    @Test fun pointerEdgesAndStickReleaseReturnToNeutral() {
        var state by mutableStateOf(VirtualGamepadState(running=true))
        compose.setContent { RemoteTheme { Box(Modifier.requiredSize(640.dp, 240.dp)) {
            GamepadPanel(state, true) {change -> state=state.copy(input=change(state.input))}
        } } }
        val a=compose.onNodeWithTag("virtual-button-a")
        a.performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(listOf("a"),state.input.buttons) }
        a.performTouchInput { up() }
        compose.runOnIdle { assertTrue(state.input.buttons.isEmpty()) }
        a.performTouchInput { down(center) }
        a.performTouchInput { moveTo(Offset(-10f,center.y)) }
        compose.runOnIdle { assertTrue(state.input.buttons.isEmpty()) }
        a.performTouchInput { up() }
        val stick=compose.onNodeWithTag("virtual-stick-right")
        stick.performTouchInput { down(centerRight) }
        compose.runOnIdle { assertTrue(state.input.rightX > .8);assertEquals(0.0,state.input.rightY,.01) }
        stick.performTouchInput { up() }
        compose.runOnIdle { assertEquals(VirtualGamepadInput(),state.input) }
    }
    @Test fun rootAndSyntheticModesExposeTheSameSixButtonsAndTwoSticks() {
        var mode by mutableStateOf("uinput")
        compose.setContent { RemoteTheme { Box(Modifier.requiredSize(390.dp, 560.dp)) {
            GamepadPanel(VirtualGamepadState(running = true, mode = mode), true) {}
        } } }
        for (value in listOf("uinput", "synthetic")) {
            compose.runOnIdle { mode = value }
            controlTags.forEach { compose.onNodeWithTag(it).assertIsDisplayed().assertIsEnabled() }
            listOf("l1", "r1", "l2", "r2", "thumb_l", "thumb_r", "hat-上", "hat-下", "hat-左", "hat-右").forEach {
                compose.onNodeWithTag("virtual-button-$it").assertDoesNotExist()
            }
            compose.onNodeWithTag("gamepad-advanced").assertDoesNotExist()
        }
    }
    @Test fun shortLandscapeAndNarrowLargeTextFitWithoutScrolling() {
        var viewport by mutableStateOf(0)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                RemoteTheme(darkTheme = viewport == 2) { Box(Modifier.requiredSize(when(viewport) { 0 -> 640.dp; 1 -> 480.dp; else -> 320.dp }, if (viewport == 2) 480.dp else 240.dp)) {
                    GamepadPanel(VirtualGamepadState(), false) {}
                } }
            }
        }
        for (value in listOf(0, 1, 2)) {
            compose.runOnIdle { viewport=value }
            val page=compose.onNodeWithTag("gamepad-page").fetchSemanticsNode().boundsInRoot
            controlTags.forEach {
                val node = compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode()
                assertEquals("$it must be entirely visible", node.size.height.toFloat(), node.boundsInRoot.height, 1f)
                assertTrue("$it must fit page width", node.boundsInRoot.left >= page.left && node.boundsInRoot.right <= page.right)
                assertTrue("$it must remain at least 48dp", node.boundsInRoot.width >= 48f && node.boundsInRoot.height >= 48f)
            }
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(0)
        }
    }
    @Test fun singleSessionIconTogglesAndBusyStatesKeepControlPositions() {
        var state by mutableStateOf(VirtualGamepadState())
        var starts=0; var stops=0
        compose.setContent { RemoteTheme { Column(Modifier.requiredSize(640.dp, 300.dp)) {
            Row { androidx.compose.material3.Text("游戏手柄"); GamepadSessionButton(state, true, {starts++}, {stops++}) }
            Box(Modifier.weight(1f)) { GamepadPanel(state, true) {} }
        } } }
        val positions=controlTags.associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        compose.onNodeWithContentDescription("开始手柄").performClick()
        assertEquals(1, starts)
        for (value in listOf(VirtualGamepadState(starting=true), VirtualGamepadState(running=true), VirtualGamepadState(stopping=true), VirtualGamepadState(running=true,reconnecting=true), VirtualGamepadState(error="fixture failure"))) {
            compose.runOnIdle { state=value }
            positions.forEach { (tag, rect) -> assertEquals(rect, compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot) }
            compose.onNodeWithText("全部释放并回中").assertDoesNotExist()
            if(value.starting || value.stopping) compose.onNodeWithTag("gamepad-session-toggle").assertIsNotEnabled()
            if(value.reconnecting) {
                compose.onNodeWithTag("virtual-button-a").assertIsNotEnabled()
                compose.onNodeWithContentDescription("正在重连手柄，点击停止").assertIsEnabled().performClick()
            } else if(value.running) compose.onNodeWithContentDescription("停止手柄").performClick()
        }
        assertEquals(2,stops)
    }
    private val controlTags = listOf("a", "b", "x", "y", "select", "start").map { "virtual-button-$it" } +
        listOf("virtual-stick-left", "virtual-stick-right")
}
