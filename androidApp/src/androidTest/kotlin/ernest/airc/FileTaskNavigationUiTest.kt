package ernest.airc

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
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

class FileTaskNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun taskEntryAndNoticeShareResultsWithoutCreationFormsAndRestore() {
        var dark by mutableStateOf(false)
        var busy by mutableStateOf(false)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RemoteTheme(dark) { Surface(Modifier.width(320.dp).height(640.dp)) {
                    FileBrowserPanel(FileBrowserState(loaded=true), true, {}, {}, {}, {}, {},
                        operationSection="download", operationActive=busy, operationMessage="有文件任务结果可查看", operationNoticeIds=listOf("fixture"),
                        taskSections=listOf("download", "url"), transfer={ section ->
                            when(section) {
                                "task:download" -> FileTransferPanel(FileTransferState(operation="download",phase="completed",readyToSave=true),"inbox","",true,{error("select")},{error("upload")},{},{},tasksOnly=true)
                                "task:url" -> UrlDownloadPanel(UrlDownloadState(phase="completed"),true,true,{error("url")},{error("name")},{error("start")},{},{},tasksOnly=true) {}
                                else -> Text("操作表单:$section")
                            }
                        })
                } }
            }
        }
        for (mode in listOf(false,true)) {
            compose.runOnIdle { dark=mode }
            compose.onNodeWithTag("files-task-view").performClick()
            compose.onNodeWithText("文件任务").assertIsDisplayed()
            compose.onNodeWithText("保存已下载文件").assertIsDisplayed()
            compose.onNodeWithText("选择本地文件").assertDoesNotExist()
            compose.onNodeWithTag("url-download-address").assertDoesNotExist()
            compose.onNodeWithText("开始下载").assertDoesNotExist()
            compose.runOnIdle { busy=true }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("文件任务").assertIsDisplayed()
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"file-tasks-${if(mode) "dark" else "light"}.png").outputStream().use {
                compose.onNodeWithTag("files-tools-dialog").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
            }
            compose.onNodeWithTag("files-tools-dialog-close").performClick()
            compose.onNodeWithTag("files-overflow").performClick()
            compose.onNodeWithText("批量传输").assertDoesNotExist()
            compose.onNodeWithText("文件操作与任务").assertDoesNotExist()
            compose.onNodeWithText("文件任务").performClick()
            compose.onNodeWithText("保存已下载文件").assertIsDisplayed()
            compose.onNodeWithTag("files-tools-dialog-close").performClick()
            compose.runOnIdle { busy=false }
        }
    }

    @Test fun emptyTasksContainNoUploadOrDownloadSelection() {
        compose.setContent { RemoteTheme { FileBrowserPanel(FileBrowserState(loaded=true),true,{},{},{},{},{},transfer={ Text("unexpected form") }) } }
        compose.onNodeWithTag("files-tools").performClick()
        compose.onNodeWithText("暂无文件任务").assertIsDisplayed()
        compose.onNodeWithText("unexpected form").assertDoesNotExist()
        compose.onNodeWithTag("files-tools-dialog-close").performClick()
        compose.onNodeWithTag("files-upload").performClick()
        compose.onNodeWithText("unexpected form").assertIsDisplayed()
    }

    @Test fun multipleUploadUsesOnePickerAndTaskPanelHasNoSecondPicker() {
        var selects=0;var uploads=0
        val batch=BatchTransferState(names=listOf("one.txt","two.txt"),uploadSelection=true)
        compose.setContent { RemoteTheme { Column {
            BatchUploadPanel(batch,FileBrowserState(),true,{selects++},{uploads++})
            BatchTransferPanel(batch.copy(paused=true),true,{},{},{},{})
        } } }
        compose.onAllNodesWithText("选择本地文件").assertCountEquals(1)
        compose.onNodeWithText("选择本地文件").performClick()
        compose.onNodeWithText("上传选中的 2 个文件").performClick()
        compose.runOnIdle { assertEquals(1,selects);assertEquals(1,uploads) }
        compose.onNodeWithText("继续原设备上的传输").assertIsEnabled()
        compose.onNodeWithText("从当前目录选择要下载的文件").assertDoesNotExist()
    }
    @Test fun completedDownloadsDoNotDisableLocalSelectionOrLeakFeedbackToUpload() {
        var names by mutableStateOf(listOf("new.txt"))
        var blocked by mutableStateOf(true)
        var available by mutableStateOf(true)
        var selected=0
        val restoration=StateRestorationTester(compose)
        restoration.setContent { RemoteTheme { Column {
            UploadFilesPanel(names,1,FileBrowserState(),available,blocked,{selected++},{_,_->error("blocked upload")},{error("blocked batch")})
            FileOperationFeedback("upload",null,"文件已分别保存，同名文件已自动改名","文件已保存")
        } } }
        compose.onNodeWithText("选择本地文件").assertIsEnabled().performClick()
        compose.onNodeWithText("推送选中文件").assertIsNotEnabled()
        compose.onNodeWithText("文件已分别保存，同名文件已自动改名").assertDoesNotExist()
        compose.onNodeWithText("文件已保存").assertDoesNotExist()
        compose.runOnIdle { names=listOf("one.txt","two.txt");available=false }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("选择本地文件").assertIsEnabled().performClick()
        compose.onNodeWithText("上传选中的 2 个文件").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2,selected);blocked=false;available=true }
        compose.onNodeWithText("选择本地文件").assertIsEnabled()
        compose.onNodeWithText("上传选中的 2 个文件").assertIsEnabled()
    }

}
