package ernest.airc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun RoutineLibraryContent(routines: List<QuickRoutine>, apps: List<InstalledApp>, connected: Boolean, run: RoutineRunState,
    onCreate: () -> Unit, onEdit: (QuickRoutine) -> Unit, onCopy: (QuickRoutine) -> Unit, onDelete: (QuickRoutine) -> Unit,
    onRun: (QuickRoutine) -> Unit, resultVisible: Boolean, runningName: String?, onStop: () -> Unit, onDismiss: () -> Unit, deviceSelected: Boolean) {
    LazyColumn(Modifier.fillMaxSize().testTag("routine-library"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (resultVisible && (run.busy || run.message != null || run.error != null)) item("result") { RoutineResultCard(run, runningName, onStop, onDismiss) }
        item("heading") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("我的快捷操作", style = MaterialTheme.typography.titleMedium)
                Text(if (routines.isEmpty()) "把常用的操作连成一步" else "${routines.size} 个组合 · 运行前可预览步骤", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                if (!connected) Text("设备离线，连接后可运行。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
            }
        }
        if (routines.isEmpty()) item("empty") {
            Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(painterResource(R.drawable.ic_remote_types), null, Modifier.size(32.dp), tint = RemoteColors.Action)
                    Text("还没有快捷操作", style = MaterialTheme.typography.titleMedium)
                    Text(if (deviceSelected) "例如：打开播放器，稍等片刻，再开始播放。" else "连接电视后，可为它创建快捷操作。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                    Button(onCreate, shape = MaterialTheme.shapes.small, enabled = !run.busy && deviceSelected, modifier = Modifier.heightIn(min = 48.dp)) { Text("创建快捷操作") }
                }
            }
        }
        items(routines, key = { it.id }) { routine ->
            var menu by remember { mutableStateOf(false) }
            Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("routine-row-${routine.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable(enabled = !run.busy, role = Role.Button) { onEdit(routine) }.padding(start = 16.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(40.dp).background(RemoteColors.ActionContainer, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_remote_types), null, Modifier.size(24.dp), tint = RemoteColors.Action)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(routine.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${routine.steps.size} 个步骤 · ${routine.steps.take(2).joinToString(" · ") { routineStepTitle(it, apps) }}", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton({ onRun(routine) }, enabled = connected && !run.busy, modifier = Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_play), "运行 ${routine.name}", tint = if (connected && !run.busy) RemoteColors.Action else RemoteColors.Disabled) }
                    Box {
                        IconButton({ menu = true }, enabled = !run.busy, modifier = Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_more), "更多 ${routine.name}") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("编辑组合") }, onClick = { menu = false; onEdit(routine) })
                            DropdownMenuItem(text = { Text("创建副本") }, enabled = routines.size < 20, onClick = { menu = false; onCopy(routine) })
                            DropdownMenuItem(text = { Text("删除组合", color = RemoteColors.Warning) }, onClick = { menu = false; onDelete(routine) })
                        }
                    }
                }
            }
        }
        if (routines.size >= 20) item("limit") { Text("已达到 20 个组合，请编辑或删除已有组合。", color = RemoteColors.Secondary) }
    }
}

@Composable
internal fun RoutineEditorContent(name: String, onName: (String) -> Unit, steps: List<RoutineStep>, apps: List<InstalledApp>, enabled: Boolean,
    onAdd: () -> Unit, onEdit: (Int) -> Unit, onMove: (Int, Int) -> Unit, onCopy: (Int) -> Unit, onDelete: (Int) -> Unit, appIcon: @Composable (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("routine-editor"), contentPadding = PaddingValues(16.dp)) {
        item("name") {
            Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, onName, label = { Text("名称") }, singleLine = true, enabled = enabled,
                        placeholder = { Text("例如：开始观影") }, modifier = Modifier.fillMaxWidth().testTag("routine-name"))
                    Text("起一个容易辨认的名字", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("操作步骤", style = MaterialTheme.typography.titleMedium)
            Text("按顺序执行，点步骤可修改", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
            Spacer(Modifier.height(12.dp))
        }
        itemsIndexed(steps) { index, step ->
            RoutineStepRow(step, index, apps, enabled, { onEdit(index) }, { onMove(index, it) },
                if (steps.size < 24 && QuickRoutine("copy", "copy", steps + step).valid()) ({ onCopy(index) }) else null,
                { onDelete(index) }, index < steps.lastIndex, appIcon)
        }
        item("add") {
            if (steps.isEmpty()) {
                Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("添加第一个步骤", style = MaterialTheme.typography.bodyLarge)
                        Text("选择打开应用、遥控按键或等待", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onAdd, shape = MaterialTheme.shapes.small, enabled = enabled && steps.size < 24, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("添加步骤")
            }
            if (steps.size >= 24) Text("已达到 24 个步骤，请编辑或移除已有步骤。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
        }
    }
}

@Composable
internal fun RoutineRunPreview(routine: QuickRoutine, apps: List<InstalledApp>, target: String, appIcon: @Composable (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("routine-run-preview"), contentPadding = PaddingValues(16.dp)) {
        item("summary") {
            Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(routine.name, style = MaterialTheme.typography.titleMedium)
                    InfoRow("目标电视", target)
                    Text("确认后将依次提交以下 ${routine.steps.size} 个步骤。执行中可停止后续步骤，已提交动作不会撤销。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        itemsIndexed(routine.steps) { index, step -> RoutineStepRow(step, index, apps, appIcon = appIcon) }
    }
}
