package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

internal fun routineStatusLabel(status: RoutineStatus): String = when (status) {
    RoutineStatus.RUNNING -> "执行中"; RoutineStatus.PAUSED -> "已暂停"; RoutineStatus.SUCCEEDED -> "执行完成"
    RoutineStatus.STOPPED -> "已停止"; RoutineStatus.FAILED -> "执行失败"
}
private fun stepStatusLabel(status: RoutineStepStatus): String = when (status) {
    RoutineStepStatus.PENDING -> "未执行"; RoutineStepStatus.RUNNING -> "执行中"; RoutineStepStatus.SUCCEEDED -> "已完成"
    RoutineStepStatus.FAILED -> "失败"; RoutineStepStatus.INTERRUPTED -> "已中断，请核对电视实际状态"
}
private fun executionTime(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))

@Composable
internal fun RoutineExecutionDetails(record: RoutineExecution, apps: List<InstalledApp>, running: Boolean, onStop: () -> Unit,
    appIcon: @Composable (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("routine-execution-details"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("summary") {
            Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(record.routine.name, style = MaterialTheme.typography.titleMedium)
                    Text(routineStatusLabel(record.status), style = MaterialTheme.typography.bodyLarge,
                        color = if (record.status == RoutineStatus.FAILED) RemoteColors.Warning else RemoteColors.Action)
                    InfoRow("目标电视", record.targetName ?: "当前电视")
                    InfoRow("开始时间", executionTime(record.startedAt))
                    record.finishedAt?.let { InfoRow("结束时间", executionTime(it)) }
                    Text("${record.steps.count { it.status == RoutineStepStatus.SUCCEEDED }} / ${record.steps.size} 步已完成", style = MaterialTheme.typography.bodyMedium)
                    Text("已提交的动作不会撤销，提交成功不代表电视应用已完成响应。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    record.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Warning) }
                    if (running) OutlinedButton(onStop, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("停止后续步骤", color = RemoteColors.Warning) }
                }
            }
        }
        itemsIndexed(record.routine.steps) { index, step ->
            val result = record.steps.getOrNull(index) ?: RoutineStepExecution()
            var expanded by remember(record.startedAt, index) { mutableStateOf(false) }
            Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                Column {
                    RoutineStepRow(step, index, apps, appIcon = appIcon)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stepStatusLabel(result.status), style = MaterialTheme.typography.bodyMedium,
                            color = if (result.status in listOf(RoutineStepStatus.FAILED, RoutineStepStatus.INTERRUPTED)) RemoteColors.Warning else RemoteColors.Secondary)
                        result.startedAt?.let { Text("开始 ${executionTime(it)}", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary) }
                        result.finishedAt?.let { Text("结束 ${executionTime(it)}", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary) }
                        if (result.output != null || result.error != null) {
                            TextButton({ expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "收起返回详情" else "查看返回详情") }
                            if (expanded) SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    result.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = RemoteColors.Warning) }
                                    result.output?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
