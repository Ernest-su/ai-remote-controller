package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FileTransferPanel(state: FileTransferState, root: String, directory: String, available: Boolean,
    onSelect: () -> Unit, onUpload: (String) -> Unit, onCancel: () -> Unit, onSave: () -> Unit, onOverwrite: ((String) -> Unit)? = null, onConfirm: (Boolean) -> Unit = {}, operation: String = state.operation, tasksOnly: Boolean = false, selectionEnabled: Boolean = !state.running) {
    state.proposal?.takeIf { state.operation == operation }?.let { proposal -> AlertDialog(onDismissRequest={onConfirm(false)},title={Text(if(proposal.kind=="delete") "确认删除" else "确认覆盖")},text={ Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("${fileRootTitle(proposal.root)}/${proposal.path}")
        proposal.sizeBytes?.let { Text("当前大小：$it 字节") }
        Text(if(proposal.kind=="delete") { if(proposal.symlink) "只删除符号链接本身，不删除链接指向的文件或目录。" else if(proposal.directory) "将删除目录及其全部内容。操作不可恢复，取消不会恢复已删除项。" else "将永久删除该项。" } else "上传校验通过后会替换此路径的文件，原文件不可恢复。")
    } },confirmButton={TextButton({onConfirm(true)}, enabled=available, modifier=Modifier.heightIn(min=48.dp)) { Text("确认",color=RemoteColors.Warning) }},dismissButton={TextButton({onConfirm(false)}, modifier=Modifier.heightIn(min=48.dp)) { Text("取消") }}) }
    var name by rememberSaveable(state.sourceName) { mutableStateOf(state.sourceName.orEmpty()) }
    if (operation == "upload" && !tasksOnly) {
    Text("推送目录：${fileRootTitle(root)}/$directory",color=RemoteColors.Secondary)
    OutlinedButton(onSelect,enabled=selectionEnabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp), shape = MaterialTheme.shapes.small) { Text("选择本地文件") }
    Text("可选择一个或多个文件，最多32个。", color = RemoteColors.Secondary)
    var overwrite by rememberSaveable { mutableStateOf(false) }
    state.sourceName?.let { source ->
        Text("来源：$source${state.sourceSize?.let{" · $it 字节"}.orEmpty()}",color=RemoteColors.Secondary)
        OutlinedTextField(name,{name=it},label={Text("目标文件名")},singleLine=true,enabled=!state.running,modifier=Modifier.fillMaxWidth())
        if(onOverwrite!=null) ToggleSettingRow("允许覆盖同名文件（需确认）", overwrite, !state.running, checkbox = true) { overwrite = it }
        ActionButton("推送选中文件",available&&!state.running&&validTargetFileName(name)) { if(overwrite)onOverwrite?.invoke(name)else onUpload(name) }
    }
    }
    val current = state.operation == operation
    if(current && state.phase!="idle") {
        val label=when(state.phase) { "awaiting_approval"->if(operation == "delete") "等待删除确认" else "等待覆盖确认";"deleting"->"正在删除";"preparing"->if(operation == "delete") "准备删除" else "准备传输";"hashing_source"->"计算源文件摘要";"hashing"->"目标计算文件摘要";"awaiting_upload"->"等待推送";"uploading"->"正在推送";"downloading"->"正在下载";"committing"->"校验并提交";"completed"->if(operation == "delete") "删除完成" else if(operation == "download") "下载完成，摘要校验通过" else "上传完成，摘要校验通过";"cancelled"->if(operation == "delete") "删除已取消，已删除项无法恢复" else "已取消，已提交文件不会删除";"cancelling"->"正在取消";else->if(operation == "delete") "删除失败" else if(operation == "download") "下载失败" else "上传失败" }
        Text(label,style=MaterialTheme.typography.bodyLarge)
        Text("${fileRootTitle(state.root)}/${state.path}",color=RemoteColors.Secondary)
        if (operation != "delete" || state.unit == "items") Text("${state.bytes}${state.total?.let{" / $it"}.orEmpty()} ${if(state.unit=="items") "项" else "字节"}",color=RemoteColors.Secondary)
    }
    if(current && state.running) { LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onCancel,modifier=Modifier.heightIn(min=48.dp)){Text(if (operation == "delete") "取消删除" else if (operation == "download") "取消下载" else "取消上传")} }
    state.error?.takeIf { current }?.let { Text(it,color=RemoteColors.Warning) }
    if(current && operation == "download" && state.readyToSave) ActionButton("保存已下载文件",!state.running,onSave)
}
