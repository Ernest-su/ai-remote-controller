package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileMediaPanel(state: FileMediaState, available: Boolean, onChoose:(String)->Unit, onOpen:()->Unit, onRelease:()->Unit, onMode:((Boolean)->Unit)?=null,onCancel:()->Unit={},onRecover:(()->Unit)?=null,onReleaseCopy:(String)->Unit={}) {
    if(onRecover==null && state.players==null && !state.busy && state.error==null && state.message==null)return
    var confirmCopy by remember(state.players?.root, state.players?.path, state.selectedPackage, state.localCopy) {mutableStateOf(false)}
    if(confirmCopy) AlertDialog(onDismissRequest={confirmCopy=false},title={Text("创建本地播放副本")},text={Column(Modifier.verticalScroll(rememberScrollState())) { Text("将在目标 Downloads 的独立目录创建可供其他应用读取的副本，占用 ${state.players?.sizeBytes?.let{"$it 字节"} ?: "额外空间"}。原文件保留；完成复制与摘要校验后打开播放器。可取消准备，或播放后释放副本。") }},confirmButton={TextButton({confirmCopy=false;onOpen()}, enabled=available&&!state.busy&&state.selectedPackage!=null, modifier=Modifier.heightIn(min=48.dp)){Text("创建并打开")}},dismissButton={TextButton({confirmCopy=false}, modifier=Modifier.heightIn(min=48.dp)){Text("取消")}})
    HorizontalDivider(color=RemoteColors.Divider)
    Text("目标媒体播放器",style=MaterialTheme.typography.titleMedium)
    onRecover?.let {query -> OutlinedButton(query,enabled=available&&!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp), shape = MaterialTheme.shapes.small){Text("查看保留的播放副本")} }
    state.retainedCopies.forEach {copy ->
        Text("Downloads/${copy.path}")
        Text("${copy.sizeBytes} 字节",color=RemoteColors.Secondary)
        OutlinedButton({onReleaseCopy(copy.id)},enabled=available&&!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp), shape = MaterialTheme.shapes.small){Text("释放此副本")}
    }
    state.players?.let { players ->
        Text("${fileRootTitle(players.root)}/${players.path}")
        Text("${players.mimeType} · ${when(players.transport){"file"->"本地文件";"local-copy"->"本地播放副本";else->"服务媒体流"}}",color=RemoteColors.Secondary)
        if(players.transport=="http") Text("需支持 HTTP 媒体地址的播放器。系统声明支持不保证实际播放；打不开时可改用 Downloads 文件。",color=RemoteColors.Secondary)
        if(players.root!="downloads" && onMode!=null) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {FilterChip(state.localCopy,{onMode(true)},enabled=available&&!state.busy,modifier=Modifier.heightIn(min=48.dp),label={Text("本地播放副本")});FilterChip(!state.localCopy,{onMode(false)},enabled=available&&!state.busy,modifier=Modifier.heightIn(min=48.dp),label={Text("HTTP 媒体流")})}
        }
        Text("系统声明支持不代表实际解码成功；无法正常播放时，请选择其他目标播放器。",color=RemoteColors.Secondary)
        if (players.packages.isEmpty()) Text("未找到声明支持此格式与来源的播放器，可更换文件或媒体来源后重试。", color=RemoteColors.Secondary)
        players.packages.forEach { name ->
            OutlinedButton({onChoose(name)},enabled=available&&!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp), shape = MaterialTheme.shapes.small) {Text(if(name==state.selectedPackage) "$name · 已选择" else name)}
        }
        ActionButton(if(state.localCopy) "创建副本并用目标播放器打开" else "用目标播放器打开",available&&!state.busy&&state.selectedPackage!=null) {if(state.localCopy)confirmCopy=true else onOpen()}
    }
    if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(state.preparingCopy) {Text("准备并校验播放副本：${state.bytes}${state.total?.let{" / $it"}.orEmpty()} 字节");TextButton(onCancel,modifier=Modifier.heightIn(min=48.dp)){Text("取消副本准备")}}
    state.message?.let {Text(it,color=RemoteColors.Secondary)}
    state.error?.let {Text(it,color=RemoteColors.Warning)}
    if(state.resourceId!=null || state.publicationId!=null) OutlinedButton(onRelease,enabled=available&&!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp), shape = MaterialTheme.shapes.small){Text("释放播放资源")}
}
