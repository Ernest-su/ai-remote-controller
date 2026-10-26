package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class FileTaskTabsUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun tabsShowCountsDisableEmptyTypesAndRestoreSelection() {
        var sections by mutableStateOf(listOf("upload","batch","download"))
        val restoration=StateRestorationTester(compose)
        restoration.setContent { RemoteTheme { Surface(Modifier.width(320.dp).height(640.dp)) {
            FileBrowserPanel(FileBrowserState(loaded=true),true,{},{},{},{},{},taskGroups=groupFileTaskSections(sections,batchUpload=true),transfer={Text("任务内容:$it")})
        } } }
        compose.onNodeWithTag("files-overflow").performClick()
        compose.onNodeWithText("文件任务").performClick()
        compose.onNodeWithText("上传(2)").assertExists()
        compose.onNodeWithTag("file-task-tab-upload").assertIsSelected()
        compose.onNodeWithText("任务内容:task:batch").assertExists()
        compose.onNodeWithTag("file-task-tab-delete").assertIsNotEnabled()
        compose.onNodeWithTag("file-task-tab-download").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("任务内容:task:download").assertExists()
        compose.onNodeWithText("任务内容:task:upload").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("file-task-tab-download").assertIsSelected()
        compose.runOnIdle {sections=listOf("upload","storage")}
        compose.onNodeWithTag("file-task-tab-download").assertIsNotEnabled()
        compose.onNodeWithTag("file-task-tab-upload").assertIsSelected()
        compose.onNodeWithTag("file-task-tab-storage").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("存储分析(1)").assertExists()
        compose.onNodeWithText("任务内容:task:storage").assertIsDisplayed()
        compose.runOnIdle { sections = listOf("media", "storage") }
        compose.onNodeWithTag("file-task-tab-storage").assertIsSelected()
        val storage = compose.onNodeWithTag("file-task-tab-storage").fetchSemanticsNode().boundsInRoot.left
        val empty = compose.onNodeWithTag("file-task-tab-upload").fetchSemanticsNode().boundsInRoot.left
        org.junit.Assert.assertTrue(storage < empty)
    }
    @Test fun allTabsAreDisabledWithoutTasks() {
        compose.setContent {RemoteTheme {FileBrowserPanel(FileBrowserState(loaded=true),true,{},{},{},{},{})}}
        if (compose.onAllNodesWithTag("files-overflow").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("files-overflow").performClick()
        compose.onNodeWithTag("files-tools").performClick()
        FileTaskType.entries.forEach {type -> compose.onNodeWithTag("file-task-tab-${type.name.lowercase()}").assertIsNotEnabled()}
        compose.onNodeWithText("暂无文件任务").assertIsDisplayed()
    }
}
