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

class AppTaskNoticeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dismissSurvivesProgressNavigationAndRestorationUntilNewTaskInBothThemes() {
        var dark by mutableStateOf(false)
        var show by mutableStateOf(true)
        var identity by mutableStateOf("first")
        var message by mutableStateOf("有应用任务结果可查看")
        var active by mutableStateOf(false)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RemoteTheme(darkTheme = dark) {
                    val pages = rememberSaveableStateHolder()
                    Surface(Modifier.width(320.dp).height(640.dp), color = RemoteColors.Background) {
                        if (show) pages.SaveableStateProvider("apps") {
                            ApplicationsPanel(emptyList(), true, false, null, "", {}, {},
                                operationMessage = message, actionsBusy = active,
                                operationNoticeIds = listOf(identity), management = { androidx.compose.material3.Text("应用任务详情") })
                        }
                    }
                }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode; identity = "$mode-first"; message = "有应用任务结果可查看"; active = false }
            compose.onNodeWithTag("apps-task-notice").assertIsDisplayed()
            compose.onNodeWithText("查看应用任务").assertDoesNotExist()
            compose.onNodeWithTag("apps-task-view").assertTextContains("查看").assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("apps-task-dismiss").assertWidthIsAtLeast(48.dp)
            val text = compose.onNodeWithTag("apps-task-message").fetchSemanticsNode().boundsInRoot
            val view = compose.onNodeWithTag("apps-task-view").fetchSemanticsNode().boundsInRoot
            val close = compose.onNodeWithTag("apps-task-dismiss").fetchSemanticsNode().boundsInRoot
            assertTrue(text.right <= view.left && view.right <= close.left)
            assertEquals(view.center.y, close.center.y, 1f)
            assertEquals(text.center.y, view.center.y, 1f)
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "app-task-notice-${if (mode) "dark" else "light"}.png").outputStream().use {
                compose.onNodeWithTag("apps-task-notice").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("apps-task-view").performClick()
            compose.onNodeWithText("应用任务详情").assertIsDisplayed()
            compose.onNodeWithTag("app-management-dialog-close").performClick()
            compose.onNodeWithTag("apps-task-dismiss").performClick()
            compose.onNodeWithTag("apps-task-notice").assertDoesNotExist()
            compose.runOnIdle { active = true; message = "应用任务正在进行，可查看进度" }
            compose.onNodeWithTag("apps-task-notice").assertDoesNotExist()
            compose.runOnIdle { active = false; message = "有应用任务结果可查看"; show = false }
            compose.runOnIdle { show = true }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("apps-task-notice").assertDoesNotExist()
            compose.runOnIdle { identity = "$mode-second" }
            compose.onNodeWithTag("apps-task-notice").assertIsDisplayed()
            compose.onNodeWithTag("apps-task-dismiss").performClick()
            compose.onNodeWithTag("apps-task-notice").assertDoesNotExist()
        }
    }
}
