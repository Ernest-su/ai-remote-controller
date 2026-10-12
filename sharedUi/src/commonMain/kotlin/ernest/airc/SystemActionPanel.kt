package ernest.airc
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SystemActionPanel(state:SystemActionState,available:Boolean,targetName:String,onPrepare:(String)->Unit,onPending:()->Unit,onConfirm:(Boolean)->Unit){
    Panel{
        SectionTitle("系统管理","锁屏、重启和关机需要 root，提交前必须确认。")
        for(action in listOf("lock","reboot","shutdown"))OutlinedButton({onPrepare(action)},enabled=available&&!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=MaterialTheme.shapes.small){Text("准备${systemActionLabel(action)}")}
        OutlinedButton(onPending,enabled=available&&!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=MaterialTheme.shapes.small){Text("查看模型待确认的系统操作")}
        state.message?.let{Text(it,color=RemoteColors.Secondary)}
        state.error?.let{Text(it,color=RemoteColors.Warning)}
        if(state.busy)Text(if(state.proposal!=null)"等待确认" else "正在处理…",color=RemoteColors.Secondary)
    }
    state.proposal?.let{p->AlertDialog(onDismissRequest={onConfirm(false)},title={Text("确认${systemActionLabel(p.action)}")},
        text={Column(Modifier.verticalScroll(rememberScrollState())){Text("目标：$targetName");Text("操作：${systemActionLabel(p.action)}");Text("运行权限：root");Text(if(p.connectionMayClose)"目标会中断当前任务和连接，关机后可能需要现场开机。提交后无法取消，断线不代表成功。" else "目标将请求锁屏，可能中断当前交互。提交后无法取消，请核对实际状态。")}},
        confirmButton={TextButton({onConfirm(true)},enabled=available,modifier=Modifier.heightIn(min=48.dp)){Text("确认${systemActionLabel(p.action)}",color=RemoteColors.Warning)}},
        dismissButton={TextButton({onConfirm(false)},modifier=Modifier.heightIn(min=48.dp)){Text("取消")}})}
}
