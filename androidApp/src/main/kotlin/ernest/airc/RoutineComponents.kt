package ernest.airc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun routineStepTitle(step: RoutineStep, apps: List<InstalledApp>): String = when (step.type) {
    "app" -> apps.find { it.packageName == step.value }?.label ?: step.value
    "wait" -> "等待 ${routineSeconds(step.value.toLongOrNull() ?: 0)} 秒"
    else -> routineKeyLabel(step.value)
}
internal fun routineStepKind(step: RoutineStep) = when (step.type) { "app" -> "打开应用"; "wait" -> "等待"; else -> "遥控按键" }
internal fun routineStepIcon(type: String) = when (type) { "app" -> R.drawable.ic_apps; "wait" -> R.drawable.ic_clock; else -> R.drawable.ic_remote_types }

@Composable
internal fun RoutineStepRow(step: RoutineStep, index: Int, apps: List<InstalledApp>, enabled: Boolean = true,
    onEdit: (() -> Unit)? = null, onMove: ((Int) -> Unit)? = null, onCopy: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
    canMoveDown: Boolean = false, appIcon: @Composable (String) -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = RemoteColors.Surface) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("routine-step-$index"), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).then(if (onEdit != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onEdit) else Modifier).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(32.dp).background(RemoteColors.Fill, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(routineStepTitle(step, apps), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(routineStepKind(step), style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    }
                    if (step.type == "app" && onEdit == null) Box(Modifier.size(RemoteDimensions.ApplicationIcon), contentAlignment = Alignment.Center) { appIcon(step.value) }
                }
                if (onEdit != null) Box {
                    IconButton({ menu = true }, enabled = enabled, modifier = Modifier.size(48.dp).testTag("routine-step-menu-$index")) { Icon(painterResource(R.drawable.ic_more), "第 ${index + 1} 步更多操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("编辑步骤") }, onClick = { menu = false; onEdit() })
                        onMove?.let { move ->
                            DropdownMenuItem(text = { Text("向前移动") }, enabled = index > 0, onClick = { menu = false; move(-1) })
                            DropdownMenuItem(text = { Text("向后移动") }, enabled = canMoveDown, onClick = { menu = false; move(1) })
                        }
                        onCopy?.let { DropdownMenuItem(text = { Text("复制步骤") }, onClick = { menu = false; it() }) }
                        onDelete?.let { DropdownMenuItem(text = { Text("移除步骤", color = RemoteColors.Warning) }, onClick = { menu = false; it() }) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start = 60.dp), color = RemoteColors.Divider, thickness = 0.5.dp)
        }
    }
}

@Composable
internal fun RoutineResultCard(run: RoutineRunState, name: String?, onStop: () -> Unit, onDismiss: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (run.busy) "正在执行${name?.let { " · $it" }.orEmpty()}" else if (run.error != null) "执行遇到问题" else "执行结果", style = MaterialTheme.typography.titleMedium)
                    Text("第 ${run.index} / ${run.total} 步", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                }
                if (!run.busy) IconButton(onDismiss, Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_close), "收起执行结果") }
            }
            if (run.busy) LinearProgressIndicator(progress = { ((run.index - 1).coerceAtLeast(0).toFloat() / run.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(if (run.error != null) "后续步骤已停止，可检查后重试。" else run.message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
            if (run.error != null) {
                TextButton({ details = !details }) { Text(if (details) "收起错误详情" else "查看错误详情") }
                if (details) Text(checkNotNull(run.error), style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Warning)
            }
            if (run.busy) OutlinedButton(onStop, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("停止后续步骤", color = RemoteColors.Warning) }
        }
    }
}
