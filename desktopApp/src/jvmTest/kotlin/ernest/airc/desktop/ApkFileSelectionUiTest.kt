package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ApkFileSelectionUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun targetApkPickerFiltersNavigatesAndOnlyInspectsAfterSelection() {
        var files by mutableStateOf(FileBrowserState(loaded = true, entries = listOf(
            TargetFileEntry("packages", "directory"), TargetFileEntry("video.mp4", "file"),
            TargetFileEntry("linked.apk", "file", symlink = true))))
        var available by mutableStateOf(true)
        var selected: String? = null
        compose.setContent { RemoteTheme { Box(Modifier.width(320.dp).height(640.dp)) {
            FileBrowserPanel(files, available, {}, {}, {},
                { files = files.copy(path = it.name, entries = listOf(TargetFileEntry("TV.APK", "file"))) }, {},
                apkInstallationAvailable = true, onApk = { selected = "${files.path}/${it.name}" },
                transfer = { section -> Text("operation:$section") })
        } } }
        compose.onNodeWithTag("files-overflow").performClick()
        compose.onNodeWithTag("files-install-apk").performClick()
        compose.onNodeWithText("video.mp4").assertDoesNotExist()
        compose.onNodeWithText("linked.apk").assertDoesNotExist()
        compose.onNodeWithText("packages").performScrollTo().performClick()
        assertNull(selected)
        compose.runOnIdle { available = false }
        compose.onNodeWithText("TV.APK").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { available = true }
        compose.onNodeWithText("TV.APK").performClick()
        assertEquals("packages/TV.APK", selected)
        compose.onNodeWithText("operation:apk").assertIsDisplayed()
    }
}
