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

class UrlDownloadConflictUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val conflict = DownloadNameConflict("inbox", "电影.mp4", "电影.mp4", "电影 (1).mp4", true)

    @Test fun editableSuggestionAndSeparateOverwriteConfirmationWorkInBothThemes() {
        var state by mutableStateOf(UrlDownloadState(conflict = conflict, noticeId = "fixture"))
        var dark by mutableStateOf(false)
        var renamed: String? = null
        var approved: Boolean? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RemoteTheme(darkTheme = dark) { Surface(Modifier.width(320.dp).fillMaxHeight(), color = RemoteColors.Background) {
                    Column { UrlDownloadPanel(state, true, true, {}, {}, {}, {}, {},
                        conflictActions = UrlDownloadConflictActions({ renamed = it; state = state.copy(conflict = null) },
                            { state = state.copy(conflict = null, overwriteProposal = FileActionProposal("fixture", "overwrite", "inbox", "电影.mp4", false, true, 1024), busy = true) },
                            { state = state.copy(conflict = null) }, { approved = it; state = state.copy(overwriteProposal = null, busy = false) }), directoryPicker = {}) }
                } }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode; state = UrlDownloadState(conflict = conflict, noticeId = "$mode-rename") }
            compose.onNodeWithTag("url-conflict-name").assertTextContains("电影 (1).mp4").performTextReplacement("电影.mp4")
            compose.onNodeWithTag("url-conflict-rename").assertIsNotEnabled()
            compose.onNodeWithTag("url-conflict-name").performTextReplacement("电影备份.mp4")
            compose.onNodeWithTag("url-conflict-rename").performClick()
            compose.runOnIdle { assertEquals("电影备份.mp4", renamed); assertNull(state.conflict) }
            compose.runOnIdle { state = UrlDownloadState(conflict = conflict, noticeId = "$mode-overwrite"); approved = null }
            compose.onNodeWithTag("url-conflict-overwrite").performClick()
            compose.onNodeWithText("确认覆盖下载文件").assertIsDisplayed()
            compose.runOnIdle { assertNull(approved) }
            compose.onNodeWithTag("url-overwrite-confirm").performClick()
            compose.runOnIdle { assertEquals(true, approved) }
        }
    }

    @Test fun directoriesAndOfflineConflictsCannotBeOverwritten() {
        var state by mutableStateOf(UrlDownloadState(conflict = conflict.copy(canOverwrite = false)))
        compose.setContent { RemoteTheme { Column {
            UrlDownloadPanel(state, false, true, {}, {}, {}, {}, {}, conflictActions = UrlDownloadConflictActions(
                { error("offline rename") }, { error("directory overwrite") }, { state = state.copy(conflict = null) }, { error("no approval") }), directoryPicker = {})
        } } }
        compose.onNodeWithTag("url-conflict-overwrite").assertDoesNotExist()
        compose.onNodeWithTag("url-conflict-rename").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertNull(state.conflict) }
    }
}
