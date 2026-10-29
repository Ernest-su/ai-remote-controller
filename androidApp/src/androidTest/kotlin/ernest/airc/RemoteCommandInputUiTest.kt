package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class RemoteCommandInputUiTest {
    val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)
    @Test fun switchingAndRestoringModesPreservesDraftWithoutStartingOfflineCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = RuntimeConnection()
        val voice = AndroidVoiceController(context, scope, runtime)
        val restore = StateRestorationTester(compose)
        try {
            restore.setContent {
                var draft by rememberSaveable { mutableStateOf("") }
                RemoteTheme { RemoteCommandInput(voice, false, false, false, draft, { draft = it }, {}) }
            }
            compose.onNodeWithTag("remote-command-input").performTextInput("保留文字草稿")
            compose.onNodeWithTag("remote-send-command").assertIsNotEnabled()
            compose.onNodeWithContentDescription("切换为语音输入").performClick()
            compose.onNodeWithContentDescription("按住说话").assertIsNotEnabled()
            compose.onNodeWithTag("remote-command-input").assertDoesNotExist()
            restore.emulateSavedInstanceStateRestore()
            compose.onNodeWithContentDescription("按住说话").assertIsNotEnabled()
            compose.onNodeWithContentDescription("切换为文字输入").performClick()
            compose.onNodeWithTag("remote-command-input").assertTextContains("保留文字草稿")
            assertFalse(voice.state.value.preparing || voice.state.value.recording)
        } finally { voice.stop(); scope.cancel(); runtime.close() }
    }
    @Test fun narrowLargeFontRemoteKeepsPowerVolumeInOneRowAndUsesIconOnlyKeys() {
        lateinit var model: RemoteViewModel
        var dark by mutableStateOf(false)
        var density = 1f
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java] }
        try {
            compose.setContent {
                density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                    RemoteTheme(dark) { Box(Modifier.width(320.dp).height(740.dp)) { RemoteControls(RemoteUiState(), model, {}) } }
                }
            }
            for (mode in listOf(false, true)) {
                compose.runOnIdle { dark = mode }
                val row = compose.onNodeWithTag("remote-power-volume").fetchSemanticsNode().boundsInRoot
                val buttons = listOf("关闭屏幕", "静音", "电源").map {
                    compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot
                }
                buttons.forEach {
                    assertTrue(it.left >= row.left - 1 && it.right <= row.right + 1)
                    assertTrue(it.width >= 48 * density - 1 && it.height >= 48 * density - 1)
                    assertEquals(buttons.first().center.y, it.center.y, 1f)
                }
                val slider = compose.onNodeWithTag("remote-volume-slider").fetchSemanticsNode().boundsInRoot
                assertTrue(slider.width >= 48 * density - 1)
                assertTrue(slider.left >= buttons[1].right - 1 && slider.right <= buttons[2].left + 1)
                for (label in listOf("上一曲", "播放 / 暂停", "下一曲", "返回", "主页", "设备菜单")) {
                    compose.onNodeWithContentDescription(label).assertIsNotEnabled()
                    compose.onAllNodesWithText(label, useUnmergedTree = true).assertCountEquals(0)
                }
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                val folder = File(compose.activity.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
                File(folder, "remote-compact-${if (mode) "dark" else "light"}.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally { model.disconnect() }
    }
}
