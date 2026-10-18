package ernest.airc

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TvInputUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun modesAndActionsSupportNarrowLargeTextInBothThemes() {
        var input by mutableStateOf(TvInputState(draft = "中文片名", mode = TvInputMode.PASTE))
        var dark by mutableStateOf(false)
        var connected by mutableStateOf(false)
        var sends = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RemoteTheme(darkTheme = dark) {
                    Column(Modifier.width(320.dp).height(640.dp).background(RemoteColors.Background)
                        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvInputContent(input, "客厅的大屏电视", connected,
                            { input = input.copy(draft = it) }, { input = input.copy(mode = it) }, { sends++ }, {}, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode; input = TvInputState(draft = "中文片名"); connected = false }
            compose.onNodeWithTag("tv-input-mode-paste").performScrollTo().assertIsSelected().assertHeightIsAtLeast(56.dp)
            capture("modes", mode)
            compose.onNodeWithTag("tv-input-send").performScrollTo().assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithText("删除一字").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("回车").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("tv-input-mode-ime").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("tv-input-enable").performScrollTo().assertIsNotEnabled()
            capture("setup", mode)
            compose.runOnIdle { connected = true }
            compose.onNodeWithTag("tv-input-send").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { input = input.copy(ime = TvImeStatus(true, true, true, true, "fixture/.Keyboard")) }
            compose.onNodeWithTag("tv-input-send").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText("恢复电视原输入法").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { input = input.copy(error = "提交未确认，请核对电视后再发送。") }
            compose.onNodeWithTag("tv-input-feedback").performScrollTo().assertIsDisplayed()
            capture("feedback", mode)
            compose.onNodeWithTag("tv-input-draft").assertTextContains("中文片名")
            compose.runOnIdle { input = input.copy(busy = true, progress = "正在提交文字…") }
            compose.onNodeWithTag("tv-input-mode-ime").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("tv-input-send").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { input = input.copy(busy = false, error = null) }
        }
        assertEquals(2, sends)
    }
    private fun capture(name: String, dark: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "tv-input-$name-${if (dark) "dark" else "light"}.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
