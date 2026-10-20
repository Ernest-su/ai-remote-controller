package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FileLinkUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun linkFileKeepsPreviewAndDownloadWhileBrokenLinkOffersPropertiesAndDeleteOnly() {
        val entry = TargetFileEntry("alias.txt", "file", 10, symlink = true, linkTarget = "real.txt")
        var downloaded: TargetFileEntry? = null
        compose.setContent { RemoteTheme {
            FileBrowserPanel(FileBrowserState(entries = listOf(entry), loaded = true), true, {}, {}, {}, {}, {},
                onDownload = { downloaded = it }, onDelete = {}, onMedia = {}, onApk = {}, previewContent = {})
        } }
        compose.onNodeWithText("指向：real.txt").assertExists()
        compose.onNodeWithTag("file-more-alias.txt").performScrollTo().performClick()
        compose.onNodeWithText("在主控设备预览 / 播放").assertExists()
        compose.onNodeWithText("在被控设备播放").assertDoesNotExist()
        compose.onNodeWithText("删除链接").assertExists()
        compose.onNodeWithText("下载文件").performClick()
        assertEquals(entry, downloaded)
    }
    @Test fun linkedDirectoryDisablesWritesAndScanButLeavesReadingAvailable() {
        compose.setContent { RemoteTheme { Box(Modifier.width(320.dp).height(640.dp)) {
            FileBrowserPanel(FileBrowserState(path = "alias", entries = listOf(TargetFileEntry("file.txt", "file", 4)), loaded = true, readOnly = true),
                true, {}, {}, {}, {}, {}, onCreateDirectory = {}, onDelete = {}, onDownload = {})
        } } }
        compose.onNodeWithTag("files-upload").assertIsNotEnabled()
        compose.onNodeWithTag("files-create-directory").assertIsNotEnabled()
        compose.onNodeWithTag("files-overflow").performClick()
        compose.onNodeWithTag("files-storage").assertIsNotEnabled()
        compose.onNodeWithTag("files-refresh").assertIsEnabled()
    }
    @Test fun deletionConfirmationExplicitlyPreservesLinkTarget() {
        compose.setContent { RemoteTheme { Column {
            FileTransferPanel(FileTransferState(operation = "delete", proposal = FileActionProposal("id", "delete", "inbox", "alias", exists = true, symlink = true)),
                "inbox", "", true, {}, {}, {}, {}, onConfirm = {}, operation = "delete")
        } } }
        compose.onNodeWithText("只删除符号链接本身，不删除链接指向的文件或目录。").assertIsDisplayed()
        compose.onNodeWithText("将删除目录及其全部内容。操作不可恢复，取消不会恢复已删除项。").assertDoesNotExist()
    }
}
