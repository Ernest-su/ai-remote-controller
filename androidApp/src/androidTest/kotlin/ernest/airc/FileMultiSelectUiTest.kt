package ernest.airc

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class FileMultiSelectUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val entries = listOf(TargetFileEntry("folder", "directory"), TargetFileEntry("a.txt", "file", 10),
        TargetFileEntry("b.txt", "file", 20), TargetFileEntry("link", "file", symlink = true), TargetFileEntry("bad:name", "file"))
    private fun scroll(name: String) { compose.onNodeWithTag("files-page").performScrollToNode(hasTestTag("file-row-$name")) }

    @Test fun listSelectionAndFilteredSelectVisibleStartOneBatchInBothThemes() {
        var dark by mutableStateOf(false)
        var names = emptyList<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RemoteTheme(dark) { Surface(Modifier.width(320.dp).height(640.dp).testTag("multi-fixture"), color = RemoteColors.Background) {
                    FileBrowserPanel(FileBrowserState(entries = entries, loaded = true), true, {}, {}, {}, {}, {},
                        onDownloadSelected = { selected, _ -> names = selected }, transfer = { Text("批量进度:$it") })
                } }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            compose.onNodeWithTag("files-multi-select").performClick()
            scroll("a.txt"); compose.onNodeWithTag("file-check-a.txt").performClick().assertIsOn()
            scroll("b.txt"); compose.onNodeWithTag("file-check-b.txt").performClick().assertIsOn()
            compose.onNodeWithTag("files-multi-count").assertTextContains("已选择 2 / 32 个文件")
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "file-multiselect-${if (mode) "dark" else "light"}.png").outputStream().use {
                compose.onNodeWithTag("multi-fixture").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("files-search").performScrollTo().performTextReplacement("a.txt")
            compose.onNodeWithTag("files-multi-count").assertTextContains("已选择 2 / 32 个文件")
            compose.onNodeWithTag("files-multi-clear").performClick()
            compose.onNodeWithTag("files-multi-download").assertIsNotEnabled()
            compose.onNodeWithTag("files-multi-select-visible").performClick()
            compose.onNodeWithTag("files-multi-count").assertTextContains("已选择 1 / 32 个文件")
            compose.onNodeWithTag("files-multi-download").assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithTag("batch-mode-confirm").performClick()
            compose.runOnIdle { assertEquals(listOf("a.txt"), names) }
            compose.onNodeWithText("批量进度:batch").assertIsDisplayed()
            compose.onNodeWithTag("files-tools-dialog-close").performClick()
            compose.onNodeWithTag("files-search").performScrollTo().performTextClearance()
            scroll("folder"); compose.onNodeWithTag("file-check-folder").assertIsNotEnabled()
            scroll("link"); compose.onNodeWithTag("file-check-link").assertIsNotEnabled()
            scroll("bad:name"); compose.onNodeWithTag("file-check-bad:name").assertIsNotEnabled()
            compose.onNodeWithTag("files-multi-cancel").performClick()
            compose.onNodeWithTag("files-multi-count").assertDoesNotExist()
        }
    }

    @Test fun gridLongPressRestoresSelectionAndNavigationThenRefreshAndBackClearIt() {
        var state by mutableStateOf(FileBrowserState(entries = entries, loaded = true))
        var show by mutableStateOf(true)
        var device by mutableStateOf("first-tv")
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RemoteTheme {
            val pages = rememberSaveableStateHolder()
            Surface(Modifier.width(320.dp).height(640.dp), color = RemoteColors.Background) {
                if (show) pages.SaveableStateProvider("files") {
                    FileBrowserPanel(state, true, {}, {}, {}, {}, {}, onDownloadSelected = { _, _ -> }, selectionDeviceKey = device,
                        selectionBackHandler = { active, cancel -> BackHandler(active, onBack = cancel) })
                }
            }
        } }
        compose.onNodeWithTag("files-overflow").performClick()
        compose.onNodeWithTag("files-view").performClick()
        scroll("a.txt"); compose.onNodeWithText("a.txt").performTouchInput { longClick() }
        compose.onNodeWithTag("file-check-a.txt").assertIsOn()
        scroll("b.txt"); compose.onNodeWithTag("file-check-b.txt").performClick()
        compose.runOnIdle { show = false }
        compose.runOnIdle { show = true }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("files-multi-count").assertTextContains("已选择 2 / 32 个文件")
        scroll("a.txt"); compose.onNodeWithTag("file-check-a.txt").assertIsOn()
        compose.runOnIdle { state = state.copy(entries = entries.filterNot { it.name == "b.txt" }) }
        compose.onNodeWithTag("files-multi-count").assertTextContains("已选择 1 / 32 个文件")
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(3000) { compose.onAllNodesWithTag("files-multi-count").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("files-multi-select").performClick()
        compose.onNodeWithTag("files-multi-select-visible").performClick()
        compose.runOnIdle { state = state.copy(path = "child") }
        compose.onNodeWithTag("files-multi-count").assertDoesNotExist()
        compose.onNodeWithTag("files-multi-select").assertIsDisplayed()
        compose.onNodeWithTag("files-multi-select").performClick()
        compose.onNodeWithTag("files-multi-select-visible").performClick()
        compose.runOnIdle { show = false; device = "other-tv" }
        compose.runOnIdle { show = true }
        compose.onNodeWithTag("files-multi-count").assertDoesNotExist()
    }

    @Test fun selectionIsCappedAt32AndOfflineOrExistingBatchDisablesDownload() {
        var online by mutableStateOf(false)
        var queueFree by mutableStateOf(true)
        var submissions = 0
        val many = (1..35).map { TargetFileEntry("file${it.toString().padStart(2, '0')}.txt", "file") }
        compose.setContent { RemoteTheme { Surface(Modifier.width(320.dp).height(640.dp), color = RemoteColors.Background) {
            FileBrowserPanel(FileBrowserState(entries = many, loaded = true, readOnly = true), online, {}, {}, {}, {}, {},
                onDownloadSelected = { _, _ -> submissions++ }, batchDownloadEnabled = queueFree, taskSections=listOf("batch"), transfer = { Text("已有批量任务") })
        } } }
        compose.onNodeWithTag("files-multi-select").performClick()
        compose.onNodeWithTag("files-multi-select-visible").performClick()
        compose.onNodeWithTag("files-multi-count").assertTextContains("已选择 32 / 32 个文件")
        compose.onNodeWithTag("files-multi-download").assertIsNotEnabled()
        scroll("file33.txt"); compose.onNodeWithTag("file-check-file33.txt").assertIsNotEnabled()
        scroll("file01.txt"); compose.onNodeWithTag("file-check-file01.txt").performClick()
        scroll("file33.txt"); compose.onNodeWithTag("file-check-file33.txt").performClick().assertIsOn()
        compose.runOnIdle { online = true; queueFree = false }
        compose.onNodeWithTag("files-multi-download").assertIsNotEnabled()
        compose.onNodeWithText("查看文件任务").performClick()
        compose.onNodeWithText("已有批量任务").assertIsDisplayed()
        compose.onNodeWithTag("files-tools-dialog-close").performClick()
        compose.runOnIdle { queueFree = true }
        compose.onNodeWithTag("files-multi-download").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, submissions) }
    }
}
