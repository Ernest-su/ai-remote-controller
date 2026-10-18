package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
internal fun SharePanel(share: IncomingShare, state: RemoteUiState, model: RemoteViewModel,
    onDevices: () -> Unit, onDownload: () -> Unit, onInput: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember(share.id) { mutableStateOf(false) }
    var reading by remember(share.id) { mutableStateOf(false) }
    var error by remember(share.id) { mutableStateOf<String?>(null) }
    var uploadedPath by remember(share.id, state.selected) { mutableStateOf<String?>(null) }
    val input by model.tvInput.state.collectAsState()
    val download by model.urlDownloads.state.collectAsState()
    val transfer by model.transfers.state.collectAsState()
    val media by model.fileMedia.state.collectAsState()
    val target = state.history.find { it.id == state.selected }
    Text("目标电视：${target?.displayName ?: target?.host ?: if (state.connected) "当前连接设备" else "尚未连接"}")
    TextButton(onDevices, modifier = Modifier.heightIn(min = 48.dp)) { Text("选择或连接电视") }
    share.error?.let { Text(it, color = RemoteColors.Warning) }
    share.text?.let { text ->
        Text(text.take(1000), color = RemoteColors.Secondary)
        if (validDownloadUrl(text)) ActionButton("用此地址下载到电视", !transfer.running && !download.busy) {
            model.urlDownloads.editUrl(text.trim()); onDownload()
        }
        ActionButton("作为电视输入编辑", !input.busy) { model.tvInput.edit(text); onInput() }
    }
    share.uri?.let { uri ->
        if (!mode) ActionButton("准备上传文件", !transfer.running && !reading) {
            reading = true
            scope.launch {
                try { model.prepareSharedUpload(uri); mode = true; error = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "无法读取分享文件。" }
                finally { reading = false }
            }
        }
        if (mode) {
            Text("保存到电视存储卡 Download。完成后可选择在电视播放。", color = RemoteColors.Secondary)
            FileTransferPanel(transfer, "downloads", "", state.connected,
                { mode = false }, { name -> uploadedPath = name; model.transfers.upload("downloads", name, state.connected) }, model.transfers::cancel, {},
                onOverwrite = { name -> uploadedPath = name; model.transfers.upload("downloads", name, state.connected, true) }, onConfirm = model.transfers::confirm)
            if (transfer.phase == "completed" && transfer.root == "downloads" && uploadedPath != null && transfer.path == uploadedPath) {
                TextButton({ model.fileMedia.inspect("downloads", transfer.path, state.connected, false) }, enabled = state.connected) { Text("选择电视播放器") }
            }
            FileMediaPanel(media, state.connected, model.fileMedia::choose, { model.fileMedia.open(state.connected) }, { model.fileMedia.release(state.connected) }, onCancel = model.fileMedia::cancel)
        }
    }
    if (reading) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = RemoteColors.Warning) }
    Text("分享只填入内容，不自动下载、上传或播放。", color = RemoteColors.Secondary)
}
