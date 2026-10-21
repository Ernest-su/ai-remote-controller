package ernest.airc

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun UploadFilesPanel(names: List<String>, size: Long?, files: FileBrowserState, available: Boolean, blocked: Boolean,
    select: () -> Unit, upload: (String, Boolean) -> Unit, uploadMany: () -> Unit) {
    if (names.size > 1) {
        BatchUploadPanel(BatchTransferState(names=names,uploadSelection=true),files,available && !blocked,select,uploadMany)
    } else {
        FileTransferPanel(FileTransferState(sourceName=names.singleOrNull(),sourceSize=size),files.root,files.path,
            available && !blocked,select,{upload(it,false)},{},{},onOverwrite={upload(it,true)},operation="upload",selectionEnabled=true)
    }
    if (blocked) Text("已有文件任务尚未完成，请在“文件任务”中处理。仍可先选择本地文件。",color=RemoteColors.Secondary)
}
