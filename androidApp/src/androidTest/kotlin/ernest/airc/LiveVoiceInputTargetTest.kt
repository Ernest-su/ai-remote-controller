package ernest.airc

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Requires an explicitly supplied isolated target; sends live microphone PCM without saving it. */
class LiveVoiceInputTargetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun heldPhoneMicrophoneStreamsToExplicitTargetAndReleases() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val host = requireNotNull(arguments.getString("voiceHost")) { "Explicit voiceHost required" }
        val port = requireNotNull(arguments.getString("voicePort")).toInt()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = AndroidVoiceController(context, scope, runtime)
        val settings = controller.state.value.settings
        try {
            controller.settings(VoiceSettings(arguments.getString("voicePackage") ?: "com.konka.bestlauncher", arguments.getString("voiceKey") ?: "1017"))
            runBlocking { runtime.connect(host, port) }
            compose.setContent { RemoteTheme { RemoteCommandInput(controller, true, false, false, "保留草稿", {}, {}) } }
            compose.waitUntil(5000) { compose.activity.hasWindowFocus() }
            compose.onNodeWithContentDescription("切换为语音输入").performClick()
            val button = compose.onNodeWithContentDescription("按住说话")
            button.performTouchInput { down(center) }
            try { compose.waitUntil(8000) { controller.state.value.recording || controller.state.value.message.startsWith("语音失败") } }
            catch (error: Throwable) { throw AssertionError(controller.state.value.toString(), error) }
            assertTrue(controller.state.value.toString(), controller.state.value.recording)
            Thread.sleep(1800) // Host driver can inspect the real assistant/submix while held.
            assertTrue(controller.state.value.toString(), controller.state.value.recording)
            button.performTouchInput { up() }
            compose.waitUntil(5000) { !controller.state.value.recording && !controller.state.value.preparing && !controller.state.value.draining }
            assertFalse(controller.state.value.toString(), controller.state.value.message.startsWith("语音失败"))
            compose.onNodeWithContentDescription("切换为文字输入").performClick()
            compose.onNodeWithTag("remote-command-input").assertTextContains("保留草稿")
        } finally {
            controller.stop(); controller.settings(settings)
            scope.cancel(); runtime.close()
        }
    }
}
