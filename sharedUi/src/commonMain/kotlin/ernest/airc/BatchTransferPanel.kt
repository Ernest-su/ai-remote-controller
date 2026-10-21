package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BatchTransferPanel(state: BatchTransferState, available: Boolean,
    pause: () -> Unit, resume: () -> Unit, cancel: () -> Unit, save: () -> Unit, saveIndividual: () -> Unit = {}) {
    Text(state.names.joinToString("、"), color = RemoteColors.Secondary)
    Text("最多32个文件，逐个传输。中断后重新连接原设备并点继续；需原被控端服务仍运行、任务未过期（闲置约15分钟）。手机进程退出或服务重启后需重新发起。",style=MaterialTheme.typography.bodySmall,color=RemoteColors.Secondary)
    state.message?.takeIf { !state.running && !state.paused && !state.readyToSave }?.let { Text(it) }
    if(state.running || state.paused || state.readyToSave) {
        Text("${state.index} / ${state.names.size} · ${state.message.orEmpty()}")
        Text("${state.bytes} / ${state.total} 字节",color=RemoteColors.Secondary)
        if(state.running) {LinearProgressIndicator(Modifier.fillMaxWidth());OutlinedButton(pause,modifier=Modifier.heightIn(min=48.dp)){Text("暂停传输")}}
        if(state.paused && !state.running) ActionButton("继续原设备上的传输",available,resume)
        if (state.readyToSave) {
            Text("下载方式：${state.downloadMode.title}")
            if (state.downloadMode == BatchDownloadMode.ZIP) ActionButton("保存 ZIP", !state.running, save)
            else {
                Text("已保存 ${state.savedNames.size} / ${state.names.size} 个文件", color = RemoteColors.Secondary)
                if (state.savedNames.size < state.names.size) ActionButton(if (state.saving) "正在保存文件" else "选择文件夹并保存文件", !state.running && !state.saving, saveIndividual)
                else Text("全部文件已保存")
            }
        }
        TextButton(cancel, enabled = !state.saving, modifier=Modifier.heightIn(min=48.dp)) {Text(if (state.readyToSave && state.downloadMode == BatchDownloadMode.INDIVIDUAL && state.savedNames.size == state.names.size) "完成并清理" else "取消并清理传输",color=RemoteColors.Warning)}
    }
    state.error?.let {Text(it,color=RemoteColors.Warning)}
}

@Composable
fun BatchUploadPanel(state: BatchTransferState, files: FileBrowserState, available: Boolean, select: () -> Unit, upload: () -> Unit, selectionEnabled: Boolean = true) {
    Text("上传目录：${fileRootTitle(files.root)}/${files.path}", color = RemoteColors.Secondary)
    OutlinedButton(select, enabled = selectionEnabled && !state.running && !state.paused && !state.saving && !state.readyToSave,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("选择本地文件") }
    Text("可选择一个或多个文件，最多32个；多个文件保留原名称，同名文件不会覆盖。", color = RemoteColors.Secondary)
    Text("已选择：" + state.names.joinToString("、"))
    if (!state.running && !state.paused && !state.readyToSave && !state.saving)
        ActionButton("上传选中的 ${state.names.size} 个文件", available && !files.readOnly && state.names.isNotEmpty(), upload)
}
