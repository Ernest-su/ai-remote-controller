package ernest.airc
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
@Composable
fun SystemObservationPanel(state:SystemObservationState,available:Boolean,onRefresh:()->Unit){
    Panel{
        SectionTitle("电源与锁屏状态","最近一次只读快照（用户 0），点击刷新更新；不代表操作已完成。")
        ActionButton(if(state.loading)"正在查询状态…" else "刷新电源与锁屏状态",available&&!state.loading,onRefresh)
        state.observation?.let{value->
            state.receivedAt?.let{InfoRow("收到时间（控制端）",it)}
            if(!value.available)Text("系统状态不可用",color=RemoteColors.Secondary)
            InfoRow("可交互",systemBooleanLabel(value.interactive))
            InfoRow("锁屏界面已锁定",systemBooleanLabel(value.keyguardLocked))
            InfoRow("设备已锁定",systemBooleanLabel(value.deviceLocked))
            InfoRow("已配置安全锁",systemBooleanLabel(value.deviceSecure))
            InfoRow("开机以来时间",value.elapsedRealtimeMs?.let{"${it/1000} 秒"} ?: "未知")
            value.reason?.let{Text(it,color=RemoteColors.Secondary)}
            if(value.errors.isNotEmpty())Text("部分字段不可用：${value.errors.keys.joinToString{when(it){"interactive"->"可交互";"keyguardLocked"->"锁屏界面";"deviceLocked"->"设备锁定";"deviceSecure"->"安全锁";else->"其他状态"}}}",color=RemoteColors.Secondary)
        }
        state.error?.let{Text(it,color=RemoteColors.Warning)}
    }
}
