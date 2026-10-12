package ernest.airc
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AppCachePanel(state:AppCacheState,available:Boolean,onConfirm:(Boolean)->Unit,onPending:(()->Unit)?) {
    state.proposal?.let { proposal -> AlertDialog(onDismissRequest={onConfirm(false)},title={Text("确认仅清理应用缓存")},
        text={Column(Modifier.verticalScroll(rememberScrollState())) { Text(proposal.app.packageName);Text("版本 ${proposal.app.versionName ?: proposal.app.versionCode} · 用户 ${proposal.app.userId}")
            Text("使用系统仅缓存接口；缓存可能包含离线下载，清理后需重新下载或生成。不会请求删除应用数据，不保证释放指定空间。提交后不能取消。") }},
        confirmButton={TextButton({onConfirm(true)},enabled=available,modifier=Modifier.heightIn(min=48.dp)){Text("确认清理缓存", color=RemoteColors.Warning)}},
        dismissButton={TextButton({onConfirm(false)},modifier=Modifier.heightIn(min=48.dp)){Text("取消")}}) }
    onPending?.let { pending -> OutlinedButton(pending,enabled=available&&!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp), shape = MaterialTheme.shapes.small){Text("查看模型待确认的缓存清理")} }
    if(state.busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text(if(state.phase=="committing")"正在清理缓存，提交后不能取消"else"正在读取/等待确认")}
    state.message?.let{Text(it,color=RemoteColors.Secondary)};state.error?.let{Text(it,color=RemoteColors.Warning)}
}
