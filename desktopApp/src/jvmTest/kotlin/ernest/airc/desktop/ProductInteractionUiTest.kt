package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProductInteractionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repeatedDeviceStatusClickPreservesTheOriginPage() {
        val navigation = DesktopNavigation()
        navigation.navigate(RemotePage.MODEL)
        navigation.navigate(RemotePage.DEVICES)
        navigation.navigate(RemotePage.DEVICES)
        navigation.back()
        assertEquals(RemotePage.MODEL, navigation.page)
    }

    @Test fun settingLabelTogglesOnceAndDisabledRowCannotChange() {
        var checked by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var calls = 0
        compose.setContent { RemoteTheme {
            ToggleSettingRow("允许覆盖同名文件（需确认）", checked, enabled, checkbox = true,
                modifier = Modifier.width(240.dp).testTag("setting")) { checked = it; calls++ }
        } }
        compose.onNodeWithText("允许覆盖同名文件（需确认）").performClick()
        compose.onNodeWithTag("setting").assertIsOn().assertHeightIsAtLeast(56.dp)
        assertEquals(1, calls)
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("setting").assertIsNotEnabled().performClick()
        assertEquals(1, calls)
    }

    @Test fun seekExplainsInvalidAndOutOfRangePositions() {
        val session = MediaSession("test.player", true, durationMs = 20000, seekSupported = true)
        compose.setContent { RemoteTheme { Box(Modifier.width(320.dp).height(640.dp)) {
            MediaControlPanel(MediaControlState(listOf(session), session.selection(), seekAvailable = true), true, {}, {}, {})
        } } }
        compose.onNodeWithTag("media-seek-position").performScrollTo().performTextInput("-1")
        compose.onNodeWithText("请输入有效的分:秒或时:分:秒").assertExists()
        compose.onNodeWithText("跳转").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("media-seek-position").performTextReplacement("0:21")
        compose.onNodeWithText("目标位置不能超过播放器报告时长").assertExists()
        compose.onNodeWithText("跳转").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("media-seek-position").performTextReplacement("0:20")
        compose.onNodeWithText("跳转").performScrollTo().assertIsEnabled()
    }

    @Test fun offlineSystemProposalRemainsReadableButCannotBeConfirmed() {
        val proposal = SystemActionProposal("fixture", "system_action", "shutdown", 0, true, true, 120)
        var confirmed = false
        compose.setContent { RemoteTheme {
            SystemActionPanel(SystemActionState(busy = true, proposal = proposal), false,
                "客厅电视".repeat(30), {}, { confirmed = it })
        } }
        compose.onNode(hasText("确认关机") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsDisplayed().performClick()
        assertFalse(confirmed)
    }

    @Test fun startingVirtualGamepadDisablesRepeatedStart() {
        compose.setContent { RemoteTheme {
            GamepadSessionButton(VirtualGamepadState(starting = true), true, {}, {})
        } }
        compose.onNodeWithContentDescription("正在开始手柄").assertIsNotEnabled()
    }

    @Test fun largeTextSettingHasOneControlAndCanWrap() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                RemoteTheme(darkTheme = true) {
                    Box(Modifier.width(240.dp)) {
                        ToggleSettingRow("优先使用 root 权限", true,
                            description = "目标支持时使用 root，否则使用普通调试权限。",
                            modifier = Modifier.testTag("setting")) {}
                    }
                }
            }
        }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNodeWithTag("setting").assertIsOn().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("目标支持时使用 root，否则使用普通调试权限。").assertIsDisplayed()
        capture("large-text-setting")
    }
    @Test fun sharedToolPagesFitNarrowLargeTextInBothThemes() {
        var screen by mutableStateOf("keypad")
        var dark by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                RemoteTheme(darkTheme = dark) {
                    Box(Modifier.width(320.dp).height(640.dp)) {
                        key(screen, dark) {
                            when (screen) {
                                "keypad" -> KeypadPanel(false, "", {})
                                "media" -> MediaControlPanel(MediaControlState(), false, {}, {}, {})
                                "touchpad" -> TouchpadPanel(false, emptyList(), false, "", { _, _ -> false })
                                "screenshot" -> ScreenshotPanel(null, false, false, null, {}, {})
                                "apps" -> ApplicationsPanel(emptyList(), false, false, null, "", {}, {})
                                "files" -> FileBrowserPanel(FileBrowserState(), false, {}, {}, {}, {}, {})
                            }
                        }
                    }
                }
            }
        }
        for (theme in listOf(false, true)) for (page in listOf("keypad", "media", "touchpad", "screenshot", "apps", "files")) {
            compose.runOnIdle { dark = theme; screen = page }
            compose.onRoot().assertIsDisplayed()
            capture("$page-${if (theme) "dark" else "light"}-large-text")
        }
    }

    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage()
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        val directory = File("build/ui-review").apply { mkdirs() }
        ImageIO.write(output, "png", File(directory, "$name.png"))
    }

}
