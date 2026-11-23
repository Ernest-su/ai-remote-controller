package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test

class MouseUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun separateButtonsAndWheelWorkInBothThemesAndDisableOffline() {
        var dark by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        val edges = mutableListOf<Pair<Int,Boolean>>()
        val scrolls = mutableListOf<Int>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.5f)) {
                RemoteTheme(darkTheme = dark) {
                    Box(Modifier.width(320.dp).height(520.dp)) {
                        MousePanel(MouseState(running = true),enabled,true,enabled,2f,{},{ mask,down -> edges += mask to down },{ scrolls += it })
                    }
                }
            }
        }
        for (theme in listOf(false,true)) {
            compose.runOnIdle { dark = theme }
            for (mask in listOf(1,4,2)) {
                compose.onNodeWithTag("mouse-button-$mask").assertIsDisplayed().assertIsEnabled()
                    .performTouchInput { down(center) }
                compose.runOnIdle { assertEquals(mask to true,edges.last()) }
                compose.onNodeWithTag("mouse-button-$mask").performTouchInput { up() }
                compose.runOnIdle { assertEquals(mask to false,edges.last()) }
            }
            compose.onNodeWithTag("mouse-session").assertDoesNotExist()
            compose.onNodeWithTag("mouse-pointing").assertDoesNotExist()
            compose.onNodeWithText("转动手机控制电视指针").assertDoesNotExist()
            compose.onNodeWithText("指针随手机移动").assertDoesNotExist()
            compose.onNodeWithText("停止鼠标").assertDoesNotExist()
            val before = edges.size
            compose.onNodeWithTag("mouse-wheel").performScrollTo()
            val wheel = compose.onNodeWithTag("mouse-wheel").fetchSemanticsNode().boundsInRoot
            val keys = compose.onNodeWithTag("mouse-buttons").fetchSemanticsNode().boundsInRoot
            val page = compose.onNodeWithTag("mouse-page").fetchSemanticsNode().boundsInRoot
            assertTrue("Wheel must be above pinned buttons",wheel.bottom <= keys.top)
            assertEquals("Button group must stay at the page bottom",page.bottom,keys.bottom,1f)
            compose.onNodeWithTag("mouse-wheel").performTouchInput { swipeUp() }
            compose.runOnIdle { assertTrue(scrolls.sum()>0); assertEquals(before,edges.size) }
        }
        compose.onNodeWithTag("mouse-button-1").performTouchInput { down(center) }
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        compose.onNodeWithTag("mouse-button-1").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1 to false,edges.last()) }
        compose.onNodeWithTag("mouse-button-1").performTouchInput { up() }
    }
    @Test fun enteringAndForegroundAndTargetChangesAutomaticallyAcquireFreshSessions() {
        var available by mutableStateOf(true)
        var resumed by mutableStateOf(true)
        var focused by mutableStateOf(true)
        var target by mutableStateOf("first")
        var visible by mutableStateOf(true)
        val events = mutableListOf<String>()
        var opens = 0
        var controllerRef: MouseController? = null
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { MouseController(scope) {
                val captured = target;
                { tool,args ->
                    when (tool) {
                        "mouse.capabilities" -> "{\"supported\":true}"
                        "mouse.open" -> { opens++; events += "open:$captured"; "{\"id\":\"$opens\"}" }
                        "mouse.close" -> { events += "close:$captured"; "{\"closed\":true}" }
                        else -> kotlinx.serialization.json.buildJsonObject {
                            put("id",args.getValue("id")); put("sequence",args.getValue("sequence")); put("submitted",kotlinx.serialization.json.JsonPrimitive(true))
                        }.toString()
                    }
                }
            } }
            controllerRef = controller
            if (visible) {
                MouseAutoSession(controller,available,target,resumed,focused)
                val state by controller.state.collectAsState()
                RemoteTheme { MousePanel(state,available,true,true,2f,{ },controller::button,controller::scroll) }
            }
        }
        compose.waitUntil(5000) { opens == 1 && controllerRef?.state?.value?.running == true }
        compose.onNodeWithTag("mouse-button-1").assertIsEnabled()
        compose.runOnIdle { resumed = false }
        compose.waitUntil(5000) { events.count { it.startsWith("close:") } == 1 }
        compose.onNodeWithTag("mouse-button-1").assertIsNotEnabled()
        compose.runOnIdle { resumed = true }
        compose.waitUntil(5000) { opens == 2 }
        compose.runOnIdle { focused = false }
        compose.waitUntil(5000) { events.count { it.startsWith("close:") } == 2 }
        compose.runOnIdle { focused = true }
        compose.waitUntil(5000) { opens == 3 }
        compose.runOnIdle { target = "second" }
        compose.waitUntil(5000) { opens == 4 }
        compose.runOnIdle {
            assertTrue(events.indexOfLast { it == "close:first" } < events.indexOf("open:second"))
            available = false
        }
        compose.waitUntil(5000) { events.count { it.startsWith("close:") } == 4 }
        compose.runOnIdle { available = true }
        compose.waitUntil(5000) { opens == 5 }
        compose.runOnIdle { visible = false }
        compose.waitUntil(5000) { events.count { it.startsWith("close:") } == 5 }
    }
    @Test fun noGyroDisablesActionsAndExplainsReason() {
        compose.setContent { RemoteTheme {
            MousePanel(MouseState(),false,false,true,2f,{},{_,_->},{})
        } }
        compose.onNodeWithTag("mouse-button-1").assertIsNotEnabled()
        compose.onNodeWithTag("mouse-session").assertDoesNotExist()
        compose.onNodeWithText("此手机没有陀螺仪，无法使用空中鼠标。").assertIsDisplayed()
    }
}
