package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import ernest.ascrcpy.scrcpy.ScrcpyState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MirrorQualityUiTest {
    val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)

    @Test fun changingQualityRestartsDecoderOnFreshSurfaceWithSelectedSize() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sessions = CopyOnWriteArrayList<PreviewQualityAdb>()
        val context = compose.activity
        val prefs = context.getSharedPreferences("preview", 0)
        val original = prefs.getString("quality", null)
        val controller = AscrcpyPreviewController(context, scope) {
            val adb = PreviewQualityAdb().also { sessions += it }
            AdbSession(adb, "fixture.invalid", 1, null)
        }
        lateinit var model: RemoteViewModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
            controller.selectQuality(PreviewQuality.CLEAR)
        }
        try {
            compose.setContent { RemoteTheme {
                AndroidScreenMirror(RemoteUiState(connected = true), model, preview = controller)
            } }
            fun awaitSize(expected: PreviewQuality) {
                try {
                    compose.waitUntil(12_000) {
                        val state = controller.state.value
                        state is ScrcpyState.Failed || (state is ScrcpyState.Streaming && state.videoSize.width == expected.config.maxSize)
                    }
                } catch (error: Throwable) {
                    throw AssertionError("Expected $expected: state=${controller.state.value}, sessions=${sessions.size}, epoch=${controller.surfaceEpoch.value}, lifecycle=${compose.activity.lifecycle.currentState}", error)
                }
                val state = controller.state.value
                assertTrue("Selected $expected must configure a valid decoder: $state", state is ScrcpyState.Streaming)
                assertEquals(expected.config.maxSize, (state as ScrcpyState.Streaming).videoSize.width)
            }
            awaitSize(PreviewQuality.CLEAR)
            for (quality in listOf(PreviewQuality.FLUENT, PreviewQuality.BALANCED, PreviewQuality.CLEAR, PreviewQuality.FLUENT)) {
                val previous = sessions.last()
                val count = sessions.size
                compose.onNodeWithText(quality.title).performClick()
                awaitSize(quality)
                assertEquals(count + 1, sessions.size)
                val command = sessions.last().command.await()
                assertTrue(command.contains("max_size=${quality.config.maxSize} "))
                assertTrue(command.contains("video_bit_rate=${quality.config.videoBitRate} "))
                assertTrue(command.contains("max_fps=${quality.config.maxFps} "))
                compose.waitUntil(5000) { previous.closed && previous.removals.get() == 1 }
            }
            val count = sessions.size
            compose.onNodeWithText(PreviewQuality.FLUENT.title).performClick()
            compose.waitForIdle()
            assertEquals("Re-selecting current quality must keep the session", count, sessions.size)
        } finally {
            controller.stop()
            scope.coroutineContext[Job]?.cancelAndJoin()
            if (original == null) prefs.edit().remove("quality").commit()
            else prefs.edit().putString("quality", original).commit()
        }
    }
}
