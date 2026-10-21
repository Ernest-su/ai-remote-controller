package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BatchDownloadModeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun choicePrecedesRequestsRestoresAcrossRotationAndSupportsBothModes() {
        var dark by mutableStateOf(false)
        val calls = mutableListOf<Pair<List<String>, BatchDownloadMode>>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RemoteTheme(dark) { Surface(Modifier.width(320.dp).height(640.dp)) {
                    FileBrowserPanel(FileBrowserState(entries = listOf(TargetFileEntry("one.txt", "file"), TargetFileEntry("two.txt", "file")), loaded = true), true,
                        {}, {}, {}, {}, {}, onDownloadSelected = { names, mode -> calls += names to mode }, transfer = { Text("下载任务进度") })
                } }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            compose.onNodeWithTag("files-multi-select").performClick()
            compose.onNodeWithTag("files-multi-select-visible").performClick()
            compose.onNodeWithTag("files-multi-download").performClick()
            compose.onNodeWithTag("batch-mode-zip").assertIsSelected()
            compose.onNodeWithTag("batch-mode-individual").performClick().assertIsSelected()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("batch-mode-individual").assertIsSelected()
            val before = calls.size
            compose.onNodeWithText("取消").performClick()
            compose.runOnIdle { assertEquals(before, calls.size) }
            compose.onNodeWithTag("files-multi-download").performClick()
            compose.onNodeWithTag("batch-mode-individual").performClick()
            compose.onNodeWithTag("batch-mode-confirm").performClick()
            compose.runOnIdle { assertEquals(listOf("one.txt", "two.txt") to BatchDownloadMode.INDIVIDUAL, calls.last()) }
            compose.onNodeWithText("下载任务进度").assertIsDisplayed()
            compose.onNodeWithTag("files-tools-dialog-close").performClick()
            compose.onNodeWithTag("files-multi-download").performClick()
            compose.onNodeWithTag("batch-mode-zip").performClick()
            compose.onNodeWithTag("batch-mode-confirm").performClick()
            compose.runOnIdle { assertEquals(BatchDownloadMode.ZIP, calls.last().second) }
            compose.onNodeWithTag("files-tools-dialog-close").performClick()
            compose.onNodeWithTag("files-multi-cancel").performClick()
        }
    }

    @Test fun individualResultsShowFolderSaveAndDisableCleanupWhileSaving() {
        var state by mutableStateOf(BatchTransferState(names = listOf("one.txt", "two.txt"), readyToSave = true, downloadMode = BatchDownloadMode.INDIVIDUAL))
        var clicked = false
        compose.setContent { RemoteTheme { Column {
            BatchTransferPanel(state, true, {}, {}, {}, { error("no ZIP") }, saveIndividual = { clicked = true })
        } } }
        compose.onNodeWithText("保存 ZIP").assertDoesNotExist()
        compose.onNodeWithText("选择文件夹并保存文件").performClick()
        compose.runOnIdle { assertTrue(clicked); state = state.copy(saving = true, savedNames = listOf("one.txt")) }
        compose.onNodeWithText("正在保存文件").assertIsNotEnabled()
        compose.onNodeWithText("取消并清理传输").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(saving = false, savedNames = state.names) }
        compose.onNodeWithText("全部文件已保存").assertIsDisplayed()
        compose.onNodeWithText("完成并清理").assertIsEnabled()
    }
}
