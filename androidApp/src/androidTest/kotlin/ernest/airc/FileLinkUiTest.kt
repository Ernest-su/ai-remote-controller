package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FileLinkUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun linksExposeTargetsAndErrorsAndLinkedDirectoriesRemainReadOnlyInBothThemes() {
        var dark by mutableStateOf(false)
        var readOnly by mutableStateOf(false)
        var inspected: TargetFileEntry? = null
        val broken = TargetFileEntry("broken", "symlink", symlink = true, linkTarget = "missing", linkError = "link_missing")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RemoteTheme(dark) { Surface(Modifier.width(320.dp).height(640.dp)) {
                    FileBrowserPanel(FileBrowserState(entries = listOf(broken), loaded = true, readOnly = readOnly), true,
                        {}, {}, {}, {}, { inspected = it }, onCreateDirectory = {}, onDelete = {})
                } }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode; readOnly = false }
            compose.onNodeWithTag("files-page").performScrollToNode(hasText("broken"))
            compose.onNodeWithText("指向：missing").assertIsDisplayed()
            compose.onNodeWithText("符号链接 · 链接目标不存在").assertIsDisplayed()
            compose.onNodeWithTag("file-more-broken").performClick()
            compose.onNodeWithText("删除链接").assertIsDisplayed()
            compose.onNodeWithText("下载文件").assertDoesNotExist()
            compose.onNodeWithText("查看属性").performClick()
            assertEquals(broken, inspected)
            compose.onNodeWithText("链接目标不存在").assertIsDisplayed()
            compose.onNodeWithTag("files-properties-dialog-close").performClick()
            compose.runOnIdle { readOnly = true }
            compose.onNodeWithTag("files-upload").assertIsNotEnabled()
            compose.onNodeWithTag("files-create-directory").assertIsNotEnabled()
            compose.onNodeWithTag("files-overflow").performClick()
            compose.onNodeWithTag("files-storage").assertIsNotEnabled()
            compose.onNodeWithTag("files-refresh").assertIsEnabled()
            assertTrue(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                .performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitForIdle()
        }
    }
}
