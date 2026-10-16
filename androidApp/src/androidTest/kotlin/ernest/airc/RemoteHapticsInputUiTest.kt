package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class ObservedHaptics(private val real: AndroidRemoteHaptics) : RemoteHaptics {
        val held = mutableSetOf<Any>()
        var starts = 0
        override fun tap() = real.tap()
        override fun startHold(key: Any) { held.add(key); starts++; real.startHold(key) }
        override fun stopHold(key: Any) { held.remove(key); real.stopHold(key) }
    }
    private fun withRemote(action: (SemanticsNodeInteraction, ObservedHaptics) -> Unit) {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/status" -> MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"haptics-input-fixture","tools":[],"modelConfigured":false}""")
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(socket: WebSocket, text: String) {
                        val id = Json.parseToJsonElement(text).jsonObject.getValue("id")
                        socket.send("""{"id":$id,"protocol":1,"ok":true,"result":"fixture"}""")
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
            val key = compose.onNodeWithContentDescription("向下")
            key.performScrollTo()
            action(key, observed)
        } finally {
            compose.runOnUiThread { model.disconnect() }
            server.shutdown()
            DeviceStore(compose.activity).remove("haptics-input-fixture")
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
