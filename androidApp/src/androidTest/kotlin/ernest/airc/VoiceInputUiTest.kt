package ernest.airc

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

class VoiceInputUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun offlineButtonDoesNotCaptureAndSettingsSurviveControllerRecreation() {
        val old = context.getSharedPreferences("voice-input", 0).all
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = RuntimeConnection()
        val controller = AndroidVoiceController(context, scope, runtime)
        try {
            compose.setContent { RemoteTheme { Column { VoiceControls(controller, false); VoiceSettingsPanel(controller) } } }
            compose.onNodeWithContentDescription("按住说话").assertIsNotEnabled()
            compose.onNodeWithTag("voice-key-code").performTextClearance()
            compose.onNodeWithTag("voice-key-code").performTextInput("1017")
            compose.runOnIdle {
                assertEquals("1017", AndroidVoiceController(context, scope, runtime).state.value.settings.keyCode)
                assertFalse(controller.state.value.recording)
            }
        } finally {
            controller.stop(); scope.cancel(); runtime.close()
            context.getSharedPreferences("voice-input", 0).edit().clear().also { e -> old.forEach { (k,v) -> e.putString(k, v as String) } }.commit()
        }
    }

    @Test fun pressStreamsPcmAndReleaseOrLeavingPageStopsWithoutResuming() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val server = MockWebServer()
        val frames = AtomicInteger()
        val stops = AtomicInteger()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/status" -> MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"voice-fixture","tools":[],"modelConfigured":false}""")
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})
                "/voice" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text == "stop") { stops.incrementAndGet(); Thread.sleep(150); webSocket.send("{\"stopped\":true}") }
                        else webSocket.send("{\"ready\":true}")
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) { assertTrue(bytes.size in 2..640 && bytes.size % 2 == 0); frames.incrementAndGet() }
                })
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = RuntimeConnection()
        val controller = if (InstrumentationRegistry.getArguments().getString("voiceCapture") == "microphone") AndroidVoiceController(context, scope, runtime)
        else AndroidVoiceController(context, scope, runtime) { object : VoiceCapture {
            private var next = 0L
            override fun start() { next = android.os.SystemClock.elapsedRealtime() }
            override fun read(buffer: ByteArray): Int {
                if (android.os.SystemClock.elapsedRealtime() < next) return 0
                next += 20
                buffer.fill(0)
                return buffer.size
            }
            override fun close() {}
        } }
        var visible by mutableStateOf(true)
        try {
            runBlocking { runtime.connect("127.0.0.1", server.port) }
            compose.setContent { RemoteTheme { if (visible) VoiceControls(controller, true) } }
            // A quick tap cancelled before the IO coroutine starts must not leave a pending state.
            compose.runOnIdle { controller.start(); controller.stop() }
            compose.waitUntil(5000) { !controller.state.value.preparing && !controller.state.value.recording }
            val initialFrames = frames.get()
            val initialStops = stops.get()
            val button = compose.onNodeWithContentDescription("按住说话")
            compose.waitUntil(5000) { compose.activity.hasWindowFocus() }
            button.performTouchInput { down(center) }
            try { compose.waitUntil(8000) { frames.get() >= initialFrames + 3 } }
            catch (error: Throwable) { throw AssertionError("Voice state: ${controller.state.value}; requests=${server.requestCount}; lifecycle=${compose.activity.lifecycle.currentState}", error) }
            button.performTouchInput { up() }
            compose.waitUntil(5000) { stops.get() == initialStops + 1 && !controller.state.value.recording }
            compose.waitForIdle()
            assertFalse(controller.state.value.preparing)
            var oldPress = 0L
            compose.runOnIdle { oldPress = checkNotNull(controller.start()) }
            val beforeRepress = frames.get()
            compose.waitUntil(8000) { frames.get() > beforeRepress + 3 }
            val beforeReplacement = frames.get()
            compose.runOnIdle {
                controller.stop(oldPress)
                assertNotNull(controller.start()) // Queue behind the 150ms remote cleanup.
                controller.stop(oldPress) // A delayed UP from the old gesture cannot cancel this one.
            }
            compose.waitUntil(8000) { frames.get() > beforeReplacement + 4 && controller.state.value.recording }
            compose.runOnIdle { controller.stop() }
            compose.waitUntil(5000) { stops.get() == initialStops + 3 && !controller.state.value.recording }
            val count = frames.get()
            button.performTouchInput { down(center) }
            compose.waitUntil(8000) { frames.get() > count + 3 }
            compose.runOnIdle { visible = false }
            compose.waitUntil(5000) { stops.get() == initialStops + 4 && !controller.state.value.recording }
            compose.runOnIdle { visible = true }
            compose.waitForIdle()
            assertFalse(controller.state.value.recording)
        } finally {
            controller.stop(); scope.cancel(); runtime.close(); server.shutdown()
        }
    }
}
