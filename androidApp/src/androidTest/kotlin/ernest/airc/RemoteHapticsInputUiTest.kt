package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real remote layout and vibrator, with every remote action answered by a local no-op server. */
class RemoteHapticsInputUiTest {
    val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)
    private class ObservedHaptics(private val real: AndroidRemoteHaptics) : RemoteHaptics {
        override fun trace(event: String) = real.trace(event)
        val held = mutableSetOf<Any>()
        var starts = 0
        override fun tap() = real.tap()
        override fun startHold(key: Any) { held.add(key); starts++; real.startHold(key) }
        override fun stopHold(key: Any) { held.remove(key); real.stopHold(key) }
    }
    private fun withRemote(label: String = "向下", requests: MutableList<JsonObject> = CopyOnWriteArrayList(), action: (SemanticsNodeInteraction, ObservedHaptics) -> Unit) {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/status" -> MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"fixture","protocol":1,"deviceId":"haptics-input-fixture","tools":[],"modelConfigured":false}"""))
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(socket: WebSocket, text: String) {
                        val request = Json.parseToJsonElement(text).jsonObject
                        val id = request.getValue("id")
                        val args = request["arguments"]?.jsonObject ?: buildJsonObject {}
                        val result = if (request["tool"]?.jsonPrimitive?.content == "remote.key") {
                            requests.add(args)
                            buildJsonObject {
                                put("operation", args["action"] ?: JsonPrimitive("key")); put("pressId", args["pressId"] ?: JsonNull)
                                put("submitted", true); put("released", args["action"]?.jsonPrimitive?.content in listOf(null, "up", "cancel"))
                            }.toString()
                        } else "fixture"
                        socket.send(buildJsonObject { put("id", id); put("protocol", 1); put("ok", true); put("result", result) }.toString())
                    }
                })
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        lateinit var model: RemoteViewModel
        lateinit var observed: ObservedHaptics
        compose.setContent {
            val scope = rememberCoroutineScope()
            val real = remember { AndroidRemoteHaptics(compose.activity, scope) }
            val feedback = remember { ObservedHaptics(real).also { observed = it } }
            DisposableEffect(real) { real.setActive(true); onDispose { real.setActive(false) } }
            model = remember { ViewModelProvider(compose.activity)[RemoteViewModel::class.java] }
            val state by model.state.collectAsState()
            RemoteTheme { CompositionLocalProvider(LocalRemoteHaptics provides feedback) {
                RemoteControls(state, model) {}
            } }
        }
        try {
            compose.runOnUiThread { model.discovered(DiscoveredService("Haptics fixture", "127.0.0.1", server.port, emptyMap())) }
            compose.waitUntil(10000) { model.state.value.connected && !model.state.value.busy }
            val key = if (label == "设备菜单" || label == "主页" || label == "返回") compose.onNodeWithText(label) else compose.onNodeWithContentDescription(label)
            key.performScrollTo()
            action(key, observed)
        } finally {
            compose.runOnUiThread { model.disconnect() }
            server.shutdown()
            DeviceStore(compose.activity).remove("haptics-input-fixture")
        }
    }
    @Test fun menuSendsDownBeforeReleaseAndKeepsTheSameGesture() {
        val requests = CopyOnWriteArrayList<JsonObject>()
        withRemote("设备菜单", requests) { key, _ ->
            key.performTouchInput { down(center) }
            compose.waitUntil(3000) { requests.any { it["action"]?.jsonPrimitive?.content == "keep" } }
            assertEquals("down", requests.first()["action"]!!.jsonPrimitive.content)
            assertTrue(requests.none { it["action"]?.jsonPrimitive?.content == "up" })
            key.performTouchInput { up() }
            compose.waitUntil(3000) { requests.lastOrNull()?.get("action")?.jsonPrimitive?.content == "up" }
            assertEquals(1, requests.count { it["action"]?.jsonPrimitive?.content == "down" })
            assertEquals(1, requests.mapNotNull { it["pressId"]?.jsonPrimitive?.content }.distinct().size)
        }
    }
    @Test fun cancellingDirectionSendsCancelledUpAndAccessibilityRemainsOneTap() {
        val requests = CopyOnWriteArrayList<JsonObject>()
        withRemote(requests = requests) { key, _ ->
            key.performTouchInput { down(center) }
            compose.waitUntil(3000) { requests.isNotEmpty() }
            key.performTouchInput { cancel() }
            compose.waitUntil(3000) { requests.lastOrNull()?.get("action")?.jsonPrimitive?.content == "cancel" }
            val size = requests.size
            key.performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(3000) { requests.size > size }
            assertNull(requests.last()["action"])
        }
    }
    @Test fun leavingRemoteCancelsItsHoldWithoutWaitingForFingerRelease() {
        val requests = CopyOnWriteArrayList<JsonObject>()
        withRemote(requests = requests) { key, _ ->
            key.performTouchInput { down(center) }
            compose.waitUntil(3000) { requests.isNotEmpty() }
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.waitUntil(3000) { requests.lastOrNull()?.get("action")?.jsonPrimitive?.content == "cancel" }
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
    }

    @Test fun rapidDownKeyTapsNeverLeaveHeldFeedback() = withRemote { key, feedback ->
        repeat(100) {
            key.performTouchInput { down(center); advanceEventTime(if (it % 2 == 0) 1 else 35); up() }
        }
        Thread.sleep(1000)
        compose.runOnIdle {
            assertEquals("Short taps must not turn into held feedback", 0, feedback.starts)
            assertTrue(feedback.held.isEmpty())
        }
    }
    @Test fun queuedPhysicalShortTapsNeverLeaveHeldFeedback() = withRemote { key, feedback ->
        physicalTaps(key, List(100) { 0L })
        assertNoLateHold(feedback)
    }
    @Test fun shortTapsAroundIndicationDelayDoNotLeaveDisplacedPressTokens() = withRemote { key, feedback ->
        // The captured failure was a 107 ms tap, racing Android's delayed
        // press indication (roughly 100 ms), rather than the long-press timeout.
        physicalTaps(key, List(60) { listOf(94L, 98L, 102L, 106L, 110L)[it % 5] })
        assertNoLateHold(feedback)
    }
    private fun physicalTaps(key: SemanticsNodeInteraction, durations: List<Long>) {
        val bounds = key.fetchSemanticsNode().boundsInWindow
        val location = IntArray(2)
        compose.runOnUiThread { compose.activity.window.decorView.getLocationOnScreen(location) }
        val x = bounds.center.x + location[0]
        val y = bounds.center.y + location[1]
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (duration in durations) {
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                if (action == MotionEvent.ACTION_UP && duration > 0) Thread.sleep(duration)
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(event, false)) } finally { event.recycle() }
            }
        }
    }
    private fun assertNoLateHold(feedback: ObservedHaptics) {
        Thread.sleep(1000)
        compose.runOnIdle {
            assertEquals("Queued short taps must not start held feedback", 0, feedback.starts)
            assertTrue(feedback.held.isEmpty())
        }
    }
    @Test fun downKeyReleaseAndCancellationStopRealHeldFeedback() = withRemote { key, feedback ->
        key.performTouchInput { down(center) }
        compose.waitUntil(3000) { feedback.held.isNotEmpty() }
        key.performTouchInput { up() }
        compose.runOnIdle { assertTrue(feedback.held.isEmpty()) }
        key.performTouchInput { down(center) }
        compose.waitUntil(3000) { feedback.held.isNotEmpty() }
        key.performTouchInput { moveTo(Offset(-100f, -100f)); up() }
        compose.runOnIdle { assertTrue(feedback.held.isEmpty()) }
        Thread.sleep(600)
        compose.runOnIdle { assertTrue(feedback.held.isEmpty()) }
    }
}
