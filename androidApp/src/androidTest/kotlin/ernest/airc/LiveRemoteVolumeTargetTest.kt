package ernest.airc

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

/** Opt-in physical-target check; the driver owns the runtime and restores audio even on failure. */
@RunWith(AndroidJUnit4::class)
class LiveRemoteVolumeTargetTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)

    @Test fun touchDragAndKeysChangeRealTargetVolumeAndThumbFollows() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("mirrorTargetHost")
        val port = args.getString("mirrorTargetPort")?.toIntOrNull()
        assumeTrue("Explicit owned target required", host != null && port != null)
        val observer = RuntimeConnection()
        var model: RemoteViewModel? = null
        fun query(): RemoteVolume = runBlocking {
            checkNotNull(parseRemoteVolume(observer.request("tool", "media.volume", buildJsonObject {}))) { "Target did not report media volume" }
        }
        fun report(label: String, value: RemoteVolume) {
            Log.i("AircVolumeTarget", "$label: actual=${value.index}/${value.max}, fraction=${value.fraction}")
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val directory = File(compose.activity.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
            File(directory, "tv-volume-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            runBlocking { observer.connect(host!!, port!!) }
            val original = query()
            compose.runOnUiThread {
                model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
                model!!.discovered(DiscoveredService("TV volume validation", host!!, port!!, emptyMap()))
            }
            val active = checkNotNull(model)
            compose.waitUntil(30000) { active.state.value.connected && active.remoteVolume.state.value?.index == original.index }
            val slider = compose.onNodeWithTag("remote-volume-slider")
            fun verifyThumb(value: RemoteVolume) {
                compose.waitUntil(10000) { active.remoteVolume.state.value?.index == value.index && !active.remoteVolume.setting.value }
                compose.waitForIdle()
                slider.assertIsEnabled()
                assertEquals(value.fraction, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
                compose.onNodeWithTag("remote-volume-value").assertTextEquals("${(value.fraction * 100).roundToInt()}%")
            }
            verifyThumb(original)
            report("initial", original)
            // Keep test adjustments close to the original volume, away from both endpoints.
            for ((label, fraction) in listOf("lower" to (original.fraction - 0.18f).coerceIn(0.1f, 0.7f),
                "higher" to original.fraction.coerceIn(0.3f, 0.9f))) {
                val before = query()
                val bounds = slider.fetchSemanticsNode().boundsInRoot
                Log.i("AircVolumeTarget", "$label gesture width=${bounds.width}, delta=${bounds.width * (fraction - before.fraction)}, touchSlop=${android.view.ViewConfiguration.get(compose.activity).scaledTouchSlop}")
                slider.performTouchInput {
                    swipe(Offset(width * before.fraction, centerY), Offset(width * fraction, centerY), 600)
                }
                compose.waitUntil(10000) { !active.remoteVolume.setting.value && active.remoteVolume.state.value?.index != before.index }
                val value = query()
                if (label == "lower") assertTrue("Dragging left must lower real TV volume", value.index < before.index)
                else assertTrue("Dragging right must raise real TV volume", value.index > before.index)
                verifyThumb(value)
                report(label, value)
            }
            var before = query()
            compose.onNodeWithContentDescription("音量 +").performTouchInput { click() }
            compose.waitUntil(10000) { active.remoteVolume.state.value!!.index > before.index }
            var actual = query()
            assertTrue(actual.index > before.index)
            verifyThumb(actual); report("key-up", actual)
            before = actual
            compose.onNodeWithContentDescription("音量 −").performTouchInput { click() }
            compose.waitUntil(10000) { active.remoteVolume.state.value!!.index < before.index }
            actual = query()
            assertTrue(actual.index < before.index)
            verifyThumb(actual); report("key-down", actual)
            // A change through another controller must also move the thumb.
            runBlocking { observer.request("tool", "media.volume", buildJsonObject {
                put("percent", (original.fraction * 100).toInt())
            }) }
            verifyThumb(query()); report("external-restored", query())
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            verifyThumb(query()); report("rotated", query())
        } catch (error: Throwable) {
            Log.e("AircVolumeTarget", "Failed: volume=${model?.remoteVolume?.state?.value}, setting=${model?.remoteVolume?.setting?.value}, feedback=${model?.state?.value?.feedback}, output=${model?.state?.value?.output}", error)
            report("failure", query())
            throw error
        } finally {
            compose.runOnUiThread { model?.disconnect() }
            observer.close()
        }
    }
}
