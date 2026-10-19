package ernest.airc

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import ernest.ascrcpy.scrcpy.ScrcpyState
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Live controller UI against an explicitly selected TV and disposable runtime/color fixture. */
class LiveFloatingRemoteTargetTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)

    private fun stage(text: String) { android.util.Log.i("AircLiveMirrorTest", text) }
    private fun videoView(): SurfaceView {
        fun find(view: View): SurfaceView? {
            if (view is SurfaceView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        var result: SurfaceView? = null
        compose.runOnUiThread { result = find(compose.activity.window.decorView) }
        return checkNotNull(result) { "Actual preview SurfaceView missing" }
    }
    private fun capture(name: String): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), "live-tv-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    @Test fun realVideoKeepsRenderingThroughFloatingRemoteInteractions(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("mirrorTargetHost")
        val port = args.getString("mirrorTargetPort")?.toIntOrNull()
        assumeTrue("Explicit independent TV runtime required", host != null && port != null)
        val observer = RuntimeConnection()
        lateinit var model: RemoteViewModel
        var initialized = false
        val prefs = instrumentation.targetContext.getSharedPreferences("remote-controls", 0)
        val hadTransparency = prefs.contains("floating-transparency")
        val originalTransparency = prefs.getInt("floating-transparency", 0)
        val backup = instrumentation.targetContext.getSharedPreferences("live-mirror-test-backup", 0)
        check(!backup.getBoolean("active", false)) { "Interrupted live fixture must be recovered first" }
        check(backup.edit().putBoolean("active", true).putBoolean("had-transparency", hadTransparency)
            .putInt("transparency", originalTransparency).commit())
        var originalQuality: PreviewQuality? = null
        var baselineJars: String? = null
        var baselinePids = emptySet<String>()
        suspend fun shell(command: String) = observer.request("tool", "device.shell", buildJsonObject { put("command", command) }).trim()
        suspend fun journals() = shell("content read --uri content://ernest.airc.mediafixture.text/color-input")
        suspend fun jars() = shell("find /data/local/tmp -maxdepth 1 -name 'airc-scrcpy-*.jar' -print")
        suspend fun pids() = shell("ps -A -o PID,ARGS").lineSequence().filter { it.contains("com.genymobile.scrcpy.Server") }
            .map { it.trim().substringBefore(' ') }.toSet()
        fun frames() = (model.ascrcpyPreview.state.value as? ScrcpyState.Streaming)?.renderedFrames ?: 0L
        fun awaitVideo(after: Long = 0) {
            var nextLog = 0L
            compose.waitUntil(60_000) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now >= nextLog) { stage("Video state: ${model.ascrcpyPreview.state.value}"); nextLog = now + 2000 }
                frames() > after || model.ascrcpyPreview.state.value is ScrcpyState.Failed
            }
            val value = model.ascrcpyPreview.state.value
            assertTrue("Real TV preview did not stream: $value", value is ScrcpyState.Streaming && value.renderedFrames > after)
        }
        suspend fun verifyVideoPixels() {
            val view = videoView()
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val samples = mutableSetOf<Int>()
            try {
                repeat(4) {
                    val done = CompletableDeferred<Int>()
                    compose.runOnUiThread { PixelCopy.request(view, bitmap, { done.complete(it) }, Handler(Looper.getMainLooper())) }
                    assertEquals(PixelCopy.SUCCESS, withTimeout(5000) { done.await() })
                    val top = bitmap.getPixel(64, 32)
                    val bottom = bitmap.getPixel(64, 96)
                    assertTrue("Video top must contain real cyan target pixels", Color.red(top) < 40 && Color.green(top) > 210 && Color.blue(top) > 210)
                    assertTrue("Video bottom must contain real magenta target pixels", Color.red(bottom) > 210 && Color.green(bottom) < 50 && Color.blue(bottom) > 210)
                    val pixels = IntArray(128 * 128)
                    bitmap.getPixels(pixels, 0, 128, 0, 0, 128, 128)
                    samples += pixels.contentHashCode()
                    delay(200)
                }
                assertTrue("Actual decoded pixels must change, rather than showing a frozen frame", samples.size >= 2)
            } finally { bitmap.recycle() }
        }
        try {
            stage("Connecting isolated TV runtime")
            val id = observer.connect(host!!, port!!).deviceId
            baselineJars = jars(); baselinePids = pids()
            observer.request("configure", arguments = buildJsonObject {
                put("baseUrl", "https://fixture.invalid/v1"); put("apiKey", "fixture-key"); put("model", "fixture-model")
            })
            val record = RememberedDevice(id, "TV live preview fixture", ConnectionMethod.TCP, host,
                args.getString("ascrcpyTargetPort")?.toIntOrNull() ?: 5555, null, port, System.currentTimeMillis())
            DeviceStore(instrumentation.targetContext).remember(record)
            compose.runOnUiThread {
                model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
                initialized = true
                originalQuality = model.ascrcpyPreview.quality.value
                model.reconnect(record)
            }
            compose.waitUntil(30_000) { model.state.value.connected && !model.state.value.busy }
            compose.onNodeWithTag("open-menu").performClick()
            compose.onNodeWithTag("feature-settings").performScrollTo().performClick()
            compose.onNodeWithTag("floating-remote-transparency").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
            assertEquals(60, model.state.value.floatingRemoteTransparency)
            capture("settings").recycle()
            compose.onNodeWithTag("navigate-back").performClick()
            compose.onNodeWithTag("feature-screenshot").performScrollTo().performClick()
            args.getString("livePreviewQuality")?.let { requested ->
                compose.onNodeWithText(requested).performClick()
            }
            stage("Waiting for actual TV H.264 decoder output")
            awaitVideo()
            verifyVideoPixels()
            stage("Actual TV pixels decoded and changing")
            val displaySize = Regex("([0-9]+)x([0-9]+)").findAll(shell("wm size")).last()
            val displayMax = maxOf(displaySize.groupValues[1].toInt(), displaySize.groupValues[2].toInt())
            for (quality in listOf(PreviewQuality.FLUENT, PreviewQuality.BALANCED, PreviewQuality.CLEAR)) {
                if (quality == model.ascrcpyPreview.quality.value) continue
                val previousView = videoView()
                compose.onNodeWithText(quality.title).performScrollTo().performClick()
                awaitVideo()
                val streamed = model.ascrcpyPreview.state.value as ScrcpyState.Streaming
                val expectedMax = minOf(displayMax, quality.config.maxSize)
                assertEquals("Actual TV stream must use selected resolution", expectedMax.toFloat(),
                    maxOf(streamed.videoSize.width, streamed.videoSize.height).toFloat(), 16f)
                assertNotSame("Quality changes require a new valid video surface", previousView, videoView())
                verifyVideoPixels()
                stage("Selected ${quality.title}: actual ${streamed.videoSize}")
            }
            val nativeView = videoView()
            val panel = compose.onNodeWithTag("mirror-floating-remote")
            val handle = compose.onNodeWithTag("mirror-remote-drag")
            compose.onNodeWithTag("mirror-toggle-remote").performClick()
            panel.assertWidthIsEqualTo(RemoteDimensions.CompactRemoteWidth)
            val density = compose.activity.resources.displayMetrics.density
            val initial = panel.fetchSemanticsNode().boundsInRoot
            val dragDelta = Offset(80f * density, 40f * density)
            handle.performTouchInput { swipe(center, center + dragDelta, 400) }
            val moved = panel.fetchSemanticsNode().boundsInRoot
            assertEquals(initial.left + dragDelta.x, moved.left, 1.5f)
            assertEquals(initial.top + dragDelta.y, moved.top, 1.5f)
            handle.performTouchInput { swipe(center, center - Offset(72f * density, 0f), 400) }
            assertEquals("Release near left edge must snap", initial.left, panel.fetchSemanticsNode().boundsInRoot.left, 1.5f)
            assertEquals("Floating drags must not inject TV touch/key events", "", journals())
            capture("inline-drag-snap").recycle()
            val beforeFull = frames()
            compose.onNodeWithTag("mirror-fullscreen").performScrollTo().performClick()
            awaitVideo(beforeFull + 3)
            assertSame("Fullscreen must retain the video SurfaceView", nativeView, videoView())
            stage("Fullscreen video continues; checking fade and wake")
            // Place the header over video pixels even when a portrait phone has letterboxing.
            val videoPosition = IntArray(2)
            compose.runOnUiThread { nativeView.getLocationInWindow(videoPosition) }
            val currentPanel = panel.fetchSemanticsNode().boundsInWindow
            val overVideo = Offset(videoPosition[0] + 16f * density - currentPanel.left,
                videoPosition[1] + 16f * density - currentPanel.top)
            handle.performTouchInput { swipe(center, center + overVideo, 400) }
            handle.performTouchInput { click() }
            compose.waitUntil(2000) { panel.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "操作中" }
            delay(250)
            val active = capture("fullscreen-60-active")
            try {
                delay(5500)
                compose.mainClock.advanceTimeBy(5500)
                compose.waitUntil(2000) { panel.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "闲置淡化" }
                delay(250)
                val faded = capture("fullscreen-60-idle")
                try {
                    // Compare backing pixels over stable cyan video, independent of phone letterboxing.
                    // At 60% transparency, 60% black backing gives ~194 green, fading gives ~224.
                    var backing = 0; var lighter = 0
                    for (y in 0 until active.height step 2) for (x in 0 until active.width step 2) {
                        val first = active.getPixel(x, y)
                        if (Color.red(first) < 10 && Color.green(first) in 184..204 && Color.blue(first) in 184..204) {
                            backing++
                            val second = faded.getPixel(x, y)
                            if (Color.red(second) < 10 && Color.green(second) in 216..234 && Color.blue(second) in 216..234) lighter++
                        }
                    }
                    assertTrue("Real video must show 60% transparent control backing: $backing pixels", backing > 200)
                    assertTrue("Idle must visibly fade the control backing over real video: $lighter/$backing", lighter > backing * 0.8f)
                } finally { faded.recycle() }
            } finally { active.recycle() }
            val beforeWake = frames()
            handle.performTouchInput { click() }
            compose.waitUntil(2000) { panel.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "操作中" }
            awaitVideo(beforeWake + 3)
            capture("fullscreen-woken").recycle()
            assertEquals("Wake must not forward touch to TV", "", journals())
            stage("Floating overlay fades, wakes, and isolates TV touch")
            compose.onNodeWithContentDescription("向下").performTouchInput { click() }
            withTimeout(10_000) {
                while (true) {
                    val rows = journals()
                    if (rows.contains("\"code\":20") && rows.contains("\"action\":0") && rows.contains("\"action\":1")) break
                    delay(100)
                }
            }
            stage("TV fixture received actual remote DOWN/UP")
            compose.onNodeWithTag("mirror-remote-layout").performClick().assertContentDescriptionEquals("切换为紧凑遥控")
            compose.onNodeWithTag("remote-command-input").assertExists()
            awaitVideo(frames() + 3)
            assertSame(nativeView, videoView())
            capture("fullscreen-full-remote").recycle()
            compose.onNodeWithTag("mirror-remote-layout").performClick().assertContentDescriptionEquals("切换为完整遥控")
            compose.onNodeWithTag("mirror-close-remote").performClick()
            panel.assertDoesNotExist()
            compose.onNodeWithTag("mirror-exit-fullscreen").performClick()
            awaitVideo(frames() + 3)
            assertSame(nativeView, videoView())
            stage("Layout/close/fullscreen changes preserve live video")
            val foregroundIntent = Intent(compose.activity.intent)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            compose.runOnUiThread { assertTrue(compose.activity.moveTaskToBack(true)) }
            compose.waitUntil(10_000) { !compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
            compose.waitUntil(10_000) { model.ascrcpyPreview.state.value == ScrcpyState.Idle }
            // Preserve the original launch intent's action/categories so ActivityScenario keeps tracking it.
            instrumentation.targetContext.startActivity(foregroundIntent)
            compose.waitUntil(10_000) { compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            awaitVideo()
            verifyVideoPixels()
            capture("resumed").recycle()
            compose.onNodeWithTag("navigate-back").performClick()
            compose.waitUntil(10_000) { model.ascrcpyPreview.state.value == ScrcpyState.Idle }
            withTimeout(15_000) { while (jars() != baselineJars || pids() != baselinePids) delay(150) }
            stage("Background/foreground recovery passed; owned encoder files/processes released")
        } finally {
            if (initialized) compose.runOnUiThread {
                model.disconnect()
                model.setFloatingRemoteTransparency(originalTransparency)
                originalQuality?.let(model.ascrcpyPreview::selectQuality)
            }
            if (!hadTransparency) prefs.edit().remove("floating-transparency").commit()
            try {
                if (baselineJars != null) withTimeout(15_000) { while (jars() != baselineJars || pids() != baselinePids) delay(150) }
            } finally { observer.close() }
            check(backup.edit().clear().commit())
        }
    }
}
