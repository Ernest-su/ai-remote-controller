package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FileWorkflowUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun narrowPageHasNoDuplicateTitleAndOverflowOpensOnlyChosenOperation() {
        compose.setContent { RemoteTheme { Box(Modifier.width(320.dp).height(640.dp)) {
            FileBrowserPanel(FileBrowserState(loaded = true), true, {}, {}, {}, {}, {}, onCreateDirectory = {}, transfer = { section -> Text("当前操作:$section") })
        } } }
        compose.onNodeWithText("文件管理").assertDoesNotExist()
        compose.onNodeWithTag("files-create-directory").assertIsDisplayed()
        compose.onNodeWithTag("files-refresh").assertIsDisplayed()
        compose.onNodeWithTag("files-view").assertDoesNotExist()
        compose.onNodeWithTag("files-overflow").assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("files-overflow-menu").assertIsDisplayed()
        compose.onNodeWithTag("files-storage").performClick()
        compose.onNodeWithText("当前操作:storage").assertExists()
        compose.onNodeWithTag("files-tool-url").assertDoesNotExist()
        compose.onNodeWithText("选择本地文件").assertDoesNotExist()
    }
    @Test fun deletionDoesNotShowUnrelatedUploadDraft() {
        compose.setContent { RemoteTheme { Column {
            FileTransferPanel(FileTransferState(operation = "delete", sourceName = "old-upload.bin", phase = "completed", unit = "items", bytes = 3), "downloads", "", true, {}, {}, {}, {})
        } } }
        compose.onNodeWithText("删除完成").assertExists()
        compose.onNodeWithText("选择本地文件").assertDoesNotExist()
        compose.onNodeWithText("目标文件名").assertDoesNotExist()
        compose.onNodeWithText("保存已下载文件").assertDoesNotExist()
    }
    @Test fun detectedDownloadNameAndSizeStayEditable() {
        var name = ""
        compose.setContent { RemoteTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            UrlDownloadPanel(UrlDownloadState(url = "https://example.test/raw", name = "电视.apk", infoReady = true, fileSize = 123456), true, true, {}, { name = it }, {}, {}, {}, {})
        } } }
        compose.onNodeWithTag("url-download-name").assertTextContains("电视.apk").performTextReplacement("我的文件.apk")
        assertEquals("我的文件.apk", name)
        compose.onNodeWithText("120.5 KiB").assertExists()
    }
    @Test fun downloadedFileOffersSaveWithoutUploadFields() {
        var saved = false
        compose.setContent { RemoteTheme { Column {
            FileTransferPanel(FileTransferState(operation = "download", sourceName = "old-upload.bin", phase = "completed", readyToSave = true), "downloads", "", true, {}, {}, {}, { saved = true })
        } } }
        compose.onNodeWithText("选择本地文件").assertDoesNotExist()
        compose.onNodeWithText("目标文件名").assertDoesNotExist()
        compose.onNodeWithText("保存已下载文件").performClick()
        assertTrue(saved)
    }

}
