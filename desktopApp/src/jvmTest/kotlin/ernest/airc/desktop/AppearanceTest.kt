package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class AppearanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun storedModeFallbackAndSystemOverride() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("unknown"))
        for (mode in ThemeMode.entries) assertEquals(mode, ThemeMode.fromStored(mode.name))
        assertFalse(ThemeMode.SYSTEM.isDark(false)); assertTrue(ThemeMode.SYSTEM.isDark(true))
        assertFalse(ThemeMode.LIGHT.isDark(true)); assertFalse(ThemeMode.LIGHT.isDark(false))
        assertTrue(ThemeMode.DARK.isDark(false)); assertTrue(ThemeMode.DARK.isDark(true))
    }

    @Test fun readableSemanticPairsInBothPalettes() {
        fun contrast(a: Color, b: Color): Float {
            val x = a.luminance(); val y = b.luminance()
            return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
        }
        for (palette in listOf(LightRemotePalette, DarkRemotePalette)) {
            for (background in listOf(palette.Background, palette.Surface, palette.Fill, palette.Header)) {
                assertTrue("Body contrast", contrast(palette.Text, background) >= 4.5)
                assertTrue("Secondary contrast", contrast(palette.Secondary, background) >= 4.5)
                assertTrue("Action contrast", contrast(palette.Action, background) >= 4.5)
                assertTrue("Error contrast", contrast(palette.Warning, background) >= 4.5)
            }
            assertTrue("Button contrast", contrast(palette.OnAction, palette.Action) >= 4.5)
            assertTrue("Error button contrast", contrast(palette.OnWarning, palette.Warning) >= 4.5)
        }
    }

    @Test fun settingsSwitchesEntirePaletteAndFollowsSystemOnlyWhenSelected() {
        var mode by mutableStateOf(ThemeMode.SYSTEM)
        var systemDark by mutableStateOf(false)
        var actual: Color? = null
        var component: Color? = null
        var text: Color? = null
        compose.setContent {
            RemoteTheme(darkTheme = mode.isDark(systemDark)) {
                val background = RemoteColors.Background
                val surface = MaterialTheme.colorScheme.surfaceContainer
                val foreground = LocalContentColor.current
                SideEffect { actual = background; component = surface; text = foreground }
                Box(Modifier.width(390.dp).height(740.dp).background(RemoteColors.Background)) {
                    AppearanceSettings(mode) { mode = it }
                }
            }
        }
        compose.onNodeWithTag("theme-system").assertIsSelected()
        compose.onNodeWithTag("theme-dark").performClick().assertIsSelected()
        compose.runOnIdle {
            assertEquals(DarkRemotePalette.Background, actual)
            assertEquals(DarkRemotePalette.Surface, component)
            assertEquals(DarkRemotePalette.Text, text)
            systemDark = true
        }
        compose.onNodeWithTag("theme-light").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(LightRemotePalette.Background, actual) }
        screenshot("settings-light")
        compose.onNodeWithTag("theme-system").performClick()
        compose.runOnIdle { assertEquals(DarkRemotePalette.Background, actual) }
        screenshot("settings-dark")
        compose.runOnIdle { systemDark = false }
        compose.runOnIdle { assertEquals(LightRemotePalette.Background, actual) }
    }

    @Test fun completeOutputIsAvailableOnDemandAndStopCanBeCancelled() {
        var show by mutableStateOf(false)
        var stopped = false
        compose.setContent {
            RemoteTheme(darkTheme = true) {
                Column {
                    OperationDetails("technical-output")
                    if (show) StopServiceConfirmation(true, { show = false }) { stopped = true; show = false }
                }
            }
        }
        compose.onNodeWithText("technical-output").assertDoesNotExist()
        compose.onNodeWithText("查看操作详情").performClick()
        compose.onNodeWithText("technical-output").assertExists()
        compose.onNodeWithText("收起操作详情").performClick()
        compose.onNodeWithText("technical-output").assertDoesNotExist()
        compose.runOnIdle { show = true }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertFalse(stopped); show = true }
        compose.onNodeWithText("停止服务").performClick()
        compose.runOnIdle { assertTrue(stopped) }
    }

    @Test fun fullscreenOverlayKeepsReadableControlsOverBothVideoExtremes() {
        fun contrast(a: Color, b: Color): Float =
            (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
        assertEquals(0f, MirrorOverlayPalette.Background.alpha)
        assertEquals(0f, MirrorOverlayPalette.Fill.alpha)
        assertTrue(MirrorOverlayPalette.Surface.alpha > 0f && MirrorOverlayPalette.Surface.alpha < 1f)
        for (video in listOf(Color.White, Color.Black)) {
            val backing = MirrorOverlayPalette.Surface.compositeOver(video)
            for (foreground in listOf(MirrorOverlayPalette.Text, MirrorOverlayPalette.Secondary,
                MirrorOverlayPalette.Action, MirrorOverlayPalette.Warning)) {
                assertTrue("Overlay contrast on $video", contrast(foreground, backing) >= 4.5f)
                assertEquals(1f, foreground.alpha)
            }
        }
    }

    @Test fun fullscreenThemeRestoresInlinePaletteWithoutRecreatingContent() {
        var full by mutableStateOf(false)
        var dark by mutableStateOf(false)
        var token: Any? = null
        var color: Color? = null
        var backing: Color? = null
        var enabled: Boolean? = null
        compose.setContent {
            RemoteTheme(dark) {
                MirrorOverlayTheme(full) {
                    val instance = remember { Any() }
                    val current = RemoteColors.Text
                    val surface = MaterialTheme.colorScheme.surface
                    val overlay = LocalMirrorOverlay.current
                    SideEffect { token = instance; color = current; backing = surface; enabled = overlay }
                }
            }
        }
        val initial = compose.runOnIdle { token }
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark; full = false }
            compose.runOnIdle { assertEquals(if (isDark) DarkRemotePalette.Text else LightRemotePalette.Text, color) }
            compose.runOnIdle { full = true }
            compose.runOnIdle {
                assertEquals(true, enabled)
                assertEquals(MirrorOverlayPalette.Text, color)
                assertEquals(MirrorOverlayPalette.Surface, backing)
                assertSame(initial, token)
                full = false
            }
            compose.runOnIdle {
                assertEquals(false, enabled)
                assertEquals(if (isDark) DarkRemotePalette.Surface else LightRemotePalette.Surface, backing)
                assertSame(initial, token)
            }
        }
    }

    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage()
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        val folder = File("build/ui-review").apply { mkdirs() }
        ImageIO.write(output, "png", File(folder, "$name.png"))
    }
}
