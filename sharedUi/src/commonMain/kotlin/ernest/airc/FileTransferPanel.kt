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
    onSelect: () -> Unit, onUpload: (String) -> Unit, onCancel: () -> Unit, onSave: () -> Unit, onOverwrite: ((String) -> Unit)? = null, onConfirm: (Boolean) -> Unit = {}) {
    state.proposal?.let { proposal -> AlertDialog(onDismissRequest={onConfirm(false)},title={Text(if(proposal.kind=="delete") "确认删除" else "确认覆盖")},text={ Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("${fileRootTitle(proposal.root)}/${proposal.path}")
        proposal.sizeBytes?.let { Text("当前大小：$it 字节") }
        Text(if(proposal.kind=="delete") { if(proposal.directory) "将删除目录及其全部内容。操作不可恢复，取消不会恢复已删除项。" else "将永久删除该项。" } else "上传校验通过后会替换此路径的文件，原文件不可恢复。")
    } },confirmButton={TextButton({onConfirm(true)}, enabled=available, modifier=Modifier.heightIn(min=48.dp)) { Text("确认",color=RemoteColors.Warning) }},dismissButton={TextButton({onConfirm(false)}, modifier=Modifier.heightIn(min=48.dp)) { Text("取消") }}) }
    var name by rememberSaveable(state.sourceName) { mutableStateOf(state.sourceName.orEmpty()) }
    HorizontalDivider(color=RemoteColors.Divider)
    Text("文件传输",style=MaterialTheme.typography.titleMedium)
    Text("推送目录：${fileRootTitle(root)}/$directory",color=RemoteColors.Secondary)
    OutlinedButton(onSelect,enabled=!state.running,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp), shape = MaterialTheme.shapes.small) { Text("选择本地文件") }
    var overwrite by rememberSaveable { mutableStateOf(false) }
    state.sourceName?.let { source ->
        Text("来源：$source${state.sourceSize?.let{" · $it 字节"}.orEmpty()}",color=RemoteColors.Secondary)
        OutlinedTextField(name,{name=it},label={Text("目标文件名")},singleLine=true,enabled=!state.running,modifier=Modifier.fillMaxWidth())
        if(onOverwrite!=null) ToggleSettingRow("允许覆盖同名文件（需确认）", overwrite, !state.running, checkbox = true) { overwrite = it }
        ActionButton("推送选中文件",available&&!state.running&&validTargetFileName(name)) { if(overwrite)onOverwrite?.invoke(name)else onUpload(name) }
    }
    if(state.phase!="idle") {
        val label=when(state.phase) { "awaiting_approval"->"等待操作确认";"deleting"->"正在删除";"preparing"->"准备传输";"hashing_source"->"计算源文件摘要";"hashing"->"目标计算文件摘要";"awaiting_upload"->"等待推送";"uploading"->"正在推送";"downloading"->"正在下载";"committing"->"校验并提交";"completed"->if(state.unit=="items") "删除完成" else "传输完成，摘要校验通过";"cancelled"->if(state.unit=="items") "已取消，已删除项无法恢复" else "已取消，已提交文件不会删除";"cancelling"->"正在取消";else->"传输失败" }
        Text(label,style=MaterialTheme.typography.bodyLarge)
        Text("${fileRootTitle(state.root)}/${state.path}",color=RemoteColors.Secondary)
        Text("${state.bytes}${state.total?.let{" / $it"}.orEmpty()} ${if(state.unit=="items") "项" else "字节"}",color=RemoteColors.Secondary)
    }
    if(state.running) { LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onCancel,modifier=Modifier.heightIn(min=48.dp)){Text("取消传输")} }
    state.error?.let { Text(it,color=RemoteColors.Warning) }
    if(state.readyToSave) ActionButton("保存已下载文件",!state.running,onSave)
}
