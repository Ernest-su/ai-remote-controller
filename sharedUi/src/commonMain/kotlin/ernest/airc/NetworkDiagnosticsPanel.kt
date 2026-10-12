package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
fun NetworkDiagnosticsPanel(state: DiagnosticState, enabled: Boolean, onRun: () -> Unit, onCancel: () -> Unit, target: String = "") = Panel {
    SectionTitle("网络诊断", "分阶段检查当前填写的服务地址，不执行配对、部署或遥控动作。")
    if (target.isNotBlank()) Text(target, color = RemoteColors.Secondary)
    ActionButton(if (state.running) "正在诊断…" else "诊断服务连接", enabled && !state.running, onRun)
    if (state.running) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onCancel) { Text("取消诊断") } }
    state.stages.forEach { stage ->
        val status = when (stage.outcome) { DiagnosticOutcome.PASSED -> "通过"; DiagnosticOutcome.FAILED -> "失败"; DiagnosticOutcome.SKIPPED -> "未检查" }
        Text("${stage.name} · $status${stage.elapsedMs?.let { " · ${it}ms" }.orEmpty()}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("diagnostic-${stage.name}"))
        Text(stage.detail, color = RemoteColors.Secondary)
    }
}
