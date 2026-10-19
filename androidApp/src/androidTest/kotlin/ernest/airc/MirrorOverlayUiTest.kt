package ernest.airc

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic video extremes expose opaque panel regressions without a remote device. */
class MirrorOverlayUiTest {
    val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)

    @Test fun controlsRemainVisibleAndGapsShowVideoWithoutPassingTouchesThrough() {
        lateinit var model: RemoteViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java] }
        var dark by mutableStateOf(false)
        var video by mutableStateOf(Color.White)
        var pattern by mutableStateOf(false)
        var full by mutableStateOf(true)
        var underlayTaps = 0
        var exits = 0
        compose.setContent {
            RemoteTheme(dark) {
                Box(Modifier.fillMaxSize()) {
                    val tile = with(LocalDensity.current) { 16.dp.toPx() }
                    Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { underlayTaps++ } }) {
                        drawRect(video)
                        if (pattern) {
                            var row = 0
                            var y = 0f
                            while (y < size.height) {
                                var column = 0
                                var x = 0f
                                while (x < size.width) {
                                    if ((column + row) % 2 == 0) drawRect(Color.Black, Offset(x, y), Size(tile, tile))
                                    column++; x += tile
                                }
                                row++; y += tile
                            }
                        }
                    }
                    MirrorFloatingRemote(RemoteUiState(connected = true, modelConfigured = true), model, {}, {}, full)
                    MirrorOverlayTheme(full) {
                        MirrorFullscreenToolbar(true, {}, { exits++ }, Modifier.align(Alignment.TopEnd))
                    }
                }
            }
        }
        for (theme in listOf(false, true)) {
            for (background in listOf(Color.White, Color.Black)) {
                compose.runOnIdle { dark = theme; video = background; pattern = false; full = true }
                compose.onNodeWithContentDescription("向上").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                compose.onNodeWithTag("mirror-close-remote").assertHeightIsAtLeast(48.dp)
                val panel = compose.onNodeWithTag("mirror-floating-remote").captureToImage()
                val pixels = panel.toPixelMap()
                // Compact controls are centered: this left margin must expose the original video.
                val gap = pixels[(panel.width * 0.025f).toInt(), (panel.height * 0.40f).toInt()]
                assertEquals(background.red, gap.red, 0.02f)
                assertEquals(background.green, gap.green, 0.02f)
                assertEquals(background.blue, gap.blue, 0.02f)
                capture("${if (background == Color.White) "white" else "black"}-${if (theme) "dark" else "light"}")
            }
        }
        compose.runOnIdle { video = Color.White; pattern = true }
        capture("checkerboard")
        compose.onNodeWithTag("mirror-floating-remote").performTouchInput { click(Offset(width * 0.025f, height * 0.40f)) }
        compose.runOnIdle { assertEquals("Transparent panel gaps must not send video touches", 0, underlayTaps) }
        compose.onRoot().performTouchInput { click(Offset(width - 20f, height - 20f)) }
        compose.runOnIdle { assertEquals(1, underlayTaps) }
        compose.onNodeWithTag("mirror-exit-fullscreen").assertContentDescriptionEquals("退出全屏").performClick()
        compose.runOnIdle { assertEquals(1, exits) }
        compose.onNodeWithContentDescription("切换为完整遥控").performClick()
        capture("full-remote")
        compose.onNodeWithTag("remote-command-input").performTextInput("全屏草稿")
        compose.runOnIdle { full = false }
        compose.onNodeWithTag("remote-command-input").assertTextContains("全屏草稿")
        compose.runOnIdle { full = true }
        compose.onNodeWithTag("remote-command-input").assertTextContains("全屏草稿")
    }
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "mirror-overlay-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
