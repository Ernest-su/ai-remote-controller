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
    "wait_app", "wait_text", "wait_playing" -> step.description() + "（${step.timeoutSeconds} 秒）"
    else -> routineKeyLabel(step.value)
}
internal fun routineStepKind(step: RoutineStep) = when (step.type) { "app" -> "打开应用"; "wait_app", "wait_text", "wait_playing" -> "条件等待"; "wait" -> "等待"; else -> "遥控按键" }
internal fun routineStepIcon(type: String) = when (type) { "app" -> R.drawable.ic_apps; "wait", "wait_app", "wait_text", "wait_playing" -> R.drawable.ic_clock; else -> R.drawable.ic_remote_types }

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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(painterResource(routineStepIcon(step.type)), null, Modifier.size(20.dp).testTag("routine-step-type-$index"), tint = RemoteColors.Secondary)
                            Text(routineStepKind(step), style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                        }
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
