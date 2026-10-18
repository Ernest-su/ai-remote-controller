package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ernest.ascrcpy.scrcpy.ScrcpyState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionChannelsPanel(state: RemoteUiState, model: RemoteViewModel, onPreview: () -> Unit) {
    val adb by model.adbStatus.collectAsState()
    val preview by model.ascrcpyPreview.state.collectAsState()
    val record = state.history.find { it.id == state.selected }
    Panel {
        SectionTitle("当前连接")
        InfoRow("遥控服务", if (state.connected) "可用" else if (state.busy) "正在恢复" else "未连接")
        InfoRow("ADB", adb)
        InfoRow("动态预览", when (preview) {
            is ScrcpyState.Streaming -> "正在显示"
            is ScrcpyState.Failed -> "不可用，请进入预览重试"
            ScrcpyState.Idle -> "未开启，进入预览后连接"
            else -> "正在启动"
        })
        Text("遥控、ADB 和预览分别连接；预览关闭不影响普通遥控。", color = RemoteColors.Secondary)
        FlowRow(Modifier.fillMaxWidth()) {
            TextButton(model::reconnectCurrentDevice, enabled = record != null && !state.busy) { Text("恢复遥控服务") }
            TextButton(model::reconnectAdbOnly, enabled = record?.adbPort != null && !state.busy) { Text("恢复 ADB") }
        }
        TextButton(onPreview, enabled = state.connected && !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("打开预览与重试") }
    }
}
