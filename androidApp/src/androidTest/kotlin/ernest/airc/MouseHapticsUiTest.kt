package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real phone haptic implementation; mouse actions remain local no-op callbacks. */
class MouseHapticsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Feedback(private val real: AndroidRemoteHaptics) : RemoteHaptics {
        val held = CopyOnWriteArraySet<Any>()
        val taps = AtomicInteger()
        override fun tap() { taps.incrementAndGet(); real.tap() }
        override fun startHold(key: Any) { held += key; real.startHold(key) }
        override fun stopHold(key: Any) { held -= key; real.stopHold(key) }
    }
    private class Fixture {
        var enabled by mutableStateOf(true)
        var preference by mutableStateOf(true)
        var visible by mutableStateOf(true)
        var mask = 0
        val edges = mutableListOf<Pair<Int,Boolean>>()
        lateinit var feedback: Feedback
        var timeout = 500L
    }
    private fun content(): Fixture {
        val fixture = Fixture()
        compose.setContent {
            val scope=rememberCoroutineScope()
            val real=remember { AndroidRemoteHaptics(compose.activity,scope) }
            val observed=remember { Feedback(real).also { fixture.feedback=it } }
            fixture.timeout=LocalViewConfiguration.current.longPressTimeoutMillis
            DisposableEffect(real,fixture.preference) {
                real.setActive(fixture.preference)
                onDispose { real.setActive(false) }
            }
            val silent=LocalRemoteHaptics.current
            RemoteTheme {
                CompositionLocalProvider(LocalRemoteHaptics provides if(fixture.preference) observed else silent) {
                    if(fixture.visible) MousePanel(MouseState(running=true),fixture.enabled,true,true,2f,{},
                        { button,down ->
                            fixture.edges += button to down
                            fixture.mask = if(down) fixture.mask or button else fixture.mask and button.inv()
                        },{})
                }
            }
        }
        return fixture
    }
    @Test fun leftAndRightStayHeldAndReleaseHapticsIndependently() {
        val fixture=content()
        val left=compose.onNodeWithTag("mouse-button-1").fetchSemanticsNode().boundsInRoot.center
        val right=compose.onNodeWithTag("mouse-button-2").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(0,left) }
        compose.waitUntil(3000) { fixture.feedback.held.size==1 }
        compose.runOnIdle { assertEquals(1,fixture.mask); assertEquals(listOf(1 to true),fixture.edges) }
        compose.onRoot().performTouchInput { down(1,right) }
        compose.waitUntil(3000) { fixture.feedback.held.size==2 }
        compose.runOnIdle { assertEquals(3,fixture.mask); assertEquals(2,fixture.feedback.taps.get()) }
        compose.onRoot().performTouchInput { up(0) }
        compose.runOnIdle { assertEquals(2,fixture.mask); assertEquals(1,fixture.feedback.held.size) }
        compose.onRoot().performTouchInput { up(1) }
        compose.runOnIdle { assertEquals(0,fixture.mask); assertTrue(fixture.feedback.held.isEmpty()) }
        compose.onNodeWithTag("mouse-button-4").performTouchInput { click() }
        Thread.sleep(fixture.timeout+100)
        compose.runOnIdle { assertEquals(3,fixture.feedback.taps.get()); assertEquals(0,fixture.mask); assertTrue(fixture.feedback.held.isEmpty()) }
    }
    @Test fun cancellationDisableLeaveAndHapticPreferenceDoNotStrandHolds() {
        val fixture=content()
        val left=compose.onNodeWithTag("mouse-button-1")
        left.performTouchInput { down(center) }
        compose.waitUntil(3000) { fixture.feedback.held.size==1 }
        left.performTouchInput { moveTo(Offset(-20f,-20f)); up() }
        compose.runOnIdle { assertEquals(0,fixture.mask); assertTrue(fixture.feedback.held.isEmpty()) }
        left.performTouchInput { down(center) }
        compose.waitUntil(3000) { fixture.feedback.held.size==1 }
        compose.runOnIdle { fixture.enabled=false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0,fixture.mask); assertTrue(fixture.feedback.held.isEmpty()) }
        left.performTouchInput { up() }
        compose.runOnIdle { fixture.enabled=true; fixture.preference=false }
        val taps=fixture.feedback.taps.get()
        left.performTouchInput { down(center) }
        Thread.sleep(fixture.timeout+100)
        compose.runOnIdle {
            assertEquals(1,fixture.mask)
            assertEquals(taps,fixture.feedback.taps.get())
            assertTrue(fixture.feedback.held.isEmpty())
        }
        left.performTouchInput { up() }
        compose.runOnIdle { fixture.preference=true }
        left.performTouchInput { down(center) }
        compose.waitUntil(3000) { fixture.feedback.held.size==1 }
        compose.runOnIdle { fixture.visible=false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0,fixture.mask); assertTrue(fixture.feedback.held.isEmpty()) }
        compose.onRoot().performTouchInput { up() }
    }
}
