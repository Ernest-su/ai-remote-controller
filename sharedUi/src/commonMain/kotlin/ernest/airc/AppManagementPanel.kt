package ernest.airc
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AppManagementPanel(state:AppManagementState,available:Boolean,onConfirm:(Boolean)->Unit){
    state.proposal?.let{proposal->AlertDialog(onDismissRequest={onConfirm(false)},title={Text("确认卸载应用")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        Text(proposal.app.packageName);Text("版本 ${proposal.app.versionName ?: proposal.app.versionCode} · 用户 ${proposal.app.userId}")
        Text("将移除此用户的应用及其数据，操作不可恢复。其他 Android 用户的安装不在本次操作范围内。")
    }},confirmButton={TextButton({onConfirm(true)}, enabled=available, modifier=Modifier.heightIn(min=48.dp)){Text("卸载并删除数据",color=RemoteColors.Warning)}},dismissButton={TextButton({onConfirm(false)}, modifier=Modifier.heightIn(min=48.dp)){Text("取消")}})}
    state.info?.let{app->InfoRow("包名",app.packageName);InfoRow("版本",app.versionName ?: app.versionCode.toString());InfoRow("Android 用户",app.userId.toString());InfoRow("类型",if(app.system)"系统应用"else"用户应用")}
    if(state.busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text(if(state.phase=="committing")"正在卸载，提交后不能取消"else"正在读取/等待确认")}
    state.message?.let{Text(it,color=RemoteColors.Secondary)};state.error?.let{Text(it,color=RemoteColors.Warning)}
}
