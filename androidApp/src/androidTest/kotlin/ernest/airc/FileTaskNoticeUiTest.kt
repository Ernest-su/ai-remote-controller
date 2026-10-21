package ernest.airc

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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

class FileTaskNoticeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dismissSurvivesProgressNavigationAndRestorationUntilNewTaskInBothThemes() {
        var dark by mutableStateOf(false)
        var show by mutableStateOf(true)
        var identity by mutableStateOf("first")
        var message by mutableStateOf("有文件任务结果可查看")
        var active by mutableStateOf(false)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RemoteTheme(darkTheme = dark) {
                    val pages = rememberSaveableStateHolder()
                    Surface(Modifier.width(320.dp).height(640.dp), color = RemoteColors.Background) {
                        if (show) pages.SaveableStateProvider("files") {
                            FileBrowserPanel(FileBrowserState(loaded = true), true, {}, {}, {}, {}, {},
                                operationMessage = message, operationSection = "storage", taskSections = listOf("storage"), operationActive = active,
                                operationNoticeIds = listOf(identity), transfer = { androidx.compose.material3.Text("任务详情:$it") })
                        }
                    }
                }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode; identity = "$mode-first"; message = "有文件任务结果可查看"; active = false }
            compose.onNodeWithTag("files-task-notice").assertIsDisplayed()
            compose.onNodeWithText("查看文件任务").assertDoesNotExist()
            compose.onNodeWithTag("files-task-view").assertTextContains("查看").assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("files-task-dismiss").assertWidthIsAtLeast(48.dp)
            val text = compose.onNodeWithTag("files-task-message").fetchSemanticsNode().boundsInRoot
            val view = compose.onNodeWithTag("files-task-view").fetchSemanticsNode().boundsInRoot
            val close = compose.onNodeWithTag("files-task-dismiss").fetchSemanticsNode().boundsInRoot
            assertTrue(text.right <= view.left && view.right <= close.left)
            assertEquals(view.center.y, close.center.y, 1f)
            assertEquals(text.center.y, view.center.y, 1f)
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "file-task-notice-${if (mode) "dark" else "light"}.png").outputStream().use {
                compose.onNodeWithTag("files-task-notice").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("files-task-view").performClick()
            compose.onNodeWithText("任务详情:task:storage").assertIsDisplayed()
            compose.onNodeWithTag("files-tools-dialog-close").performClick()
            compose.onNodeWithTag("files-task-dismiss").performClick()
            compose.onNodeWithTag("files-task-notice").assertDoesNotExist()
            compose.runOnIdle { active = true; message = "文件任务正在进行，可查看进度" }
            compose.onNodeWithTag("files-task-notice").assertDoesNotExist()
            compose.runOnIdle { active = false; message = "有文件任务结果可查看"; show = false }
            compose.runOnIdle { show = true }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("files-task-notice").assertDoesNotExist()
            compose.runOnIdle { identity = "$mode-second" }
            compose.onNodeWithTag("files-task-notice").assertIsDisplayed()
            compose.onNodeWithTag("files-task-dismiss").performClick()
            compose.onNodeWithTag("files-task-notice").assertDoesNotExist()
        }
    }
}
