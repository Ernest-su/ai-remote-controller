package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun UrlDownloadPanel(
    state: UrlDownloadState, available: Boolean, validUrl: Boolean,
    onUrl: (String) -> Unit, onName: (String) -> Unit, onStart: () -> Unit, onCancel: () -> Unit,
    onDefaultDirectory: () -> Unit, directoryPicker: @Composable (() -> Unit) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    if(picking) {
        Text("选择被控设备保存目录", style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ picking = false }, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回下载表单") }
        Box(Modifier.fillMaxWidth().height(RemoteDimensions.ManagementDialogHeight)) { directoryPicker { picking = false } }
        return
    }
    Text("由被控设备访问网址并保存文件。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
    OutlinedTextField(state.url, onUrl, enabled = !state.busy, label = { Text("下载地址") },
        modifier = Modifier.fillMaxWidth().testTag("url-download-address"), maxLines = 3,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    OutlinedTextField(state.name, onName, enabled = !state.busy, label = { Text("文件名称（可选）") },
        supportingText = { Text("自动识别文件名，可自行修改；留空时按网址命名。") },
        modifier = Modifier.fillMaxWidth().testTag("url-download-name"), singleLine = true,
        isError = state.name.isNotBlank() && !validTargetFileName(state.name.trim()))
    when {
        state.infoLoading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在识别文件信息…", modifier = Modifier.testTag("url-download-info-loading"), color = RemoteColors.Secondary) }
        state.infoReady -> InfoRow("文件大小", state.fileSize?.let(::managementFileSize) ?: "服务器未提供大小，下载时显示实际进度")
        state.infoError != null -> Text(state.infoError.orEmpty(), color = RemoteColors.Secondary, modifier = Modifier.testTag("url-download-info-error"))
    }
    InfoRow("保存目录", if(state.root == "downloads" && state.directory.isEmpty()) "存储卡 Download（默认）" else "${fileRootTitle(state.root)}/${state.directory}")
    OutlinedButton({ picking = true }, shape = MaterialTheme.shapes.small, enabled = available && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("选择保存目录") }
    if(state.root != "downloads" || state.directory.isNotEmpty())
        TextButton(onDefaultDirectory, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("恢复默认下载目录") }
    Text("同名文件不会覆盖，请更换名称后重试。", color = RemoteColors.Secondary)
    if(!available) Text("连接支持网址下载的配套服务后可开始。", color = RemoteColors.Secondary)
    ActionButton(if(state.phase == "failed") "重试下载" else "开始下载",
        available && !state.busy && validUrl && (state.name.isBlank() || validTargetFileName(state.name.trim())), onStart)
    if(state.phase != "idle") {
        Text(when(state.phase) {
            "connecting" -> "正在连接下载地址"
            "downloading" -> "正在下载"
            "committing" -> "正在保存文件"
            "completed" -> "下载完成"
            "cancelled" -> "下载已取消"
            "cancelling" -> "正在取消下载"
            else -> "下载失败"
        }, modifier = Modifier.testTag("url-download-status"))
        state.destination?.let { InfoRow("目标文件", it) }
        val total = state.total
        if(total != null && total > 0) {
            val progress = (state.bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("${(progress * 100).toInt()}% · ${managementFileSize(state.bytes)} / ${managementFileSize(total)}")
        } else {
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("已下载 ${managementFileSize(state.bytes)}" + if(state.busy && total == null) " · 总大小未知" else "")
        }
    }
    state.error?.let { Text(it, color = RemoteColors.Warning) }
    if(state.busy) OutlinedButton(onCancel, shape = MaterialTheme.shapes.small, enabled = state.phase !in listOf("committing", "cancelling"),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消下载") }
}
