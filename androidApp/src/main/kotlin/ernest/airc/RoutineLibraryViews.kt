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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun RoutineLibraryContent(routines: List<QuickRoutine>, apps: List<InstalledApp>, connected: Boolean, run: RoutineRunState, lastRuns: Map<String, RoutineExecution>,
    onCreate: () -> Unit, onEdit: (QuickRoutine) -> Unit, onCopy: (QuickRoutine) -> Unit, onDelete: (QuickRoutine) -> Unit,
    onRun: (QuickRoutine) -> Unit, onDetails: (QuickRoutine) -> Unit, deviceSelected: Boolean, onPin: (QuickRoutine) -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize().testTag("routine-library"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("heading") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("我的快捷操作", style = MaterialTheme.typography.titleMedium)
                Text(if (routines.isEmpty()) "把常用的操作连成一步" else "${routines.size} 个组合 · 点右侧按钮执行", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
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
            val active = run.busy && run.routineId == routine.id
            val record = lastRuns[routine.id]
            val status = if (active) when { run.paused -> "已暂停 · 第 ${run.index} / ${run.total} 步"; run.pauseRequested -> "当前步骤结束后暂停"; else -> "执行中 · 第 ${run.index} / ${run.total} 步" }
                else record?.let { routineStatusLabel(it.status) }
            val action = if (active) { if (run.pauseRequested) "继续" else "暂停" } else if (record?.status == RoutineStatus.FAILED) "重新执行" else "运行"
            val drawable = if (active && !run.pauseRequested) R.drawable.ic_pause else if (!active && record?.status == RoutineStatus.FAILED) R.drawable.ic_error else R.drawable.ic_play
            Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("routine-row-${routine.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable(enabled = !run.busy, role = Role.Button) { onEdit(routine) }.padding(start = 16.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(40.dp).background(RemoteColors.ActionContainer, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_remote_types), null, Modifier.size(24.dp), tint = RemoteColors.Action)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(routine.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, color = if (record?.status == RoutineStatus.FAILED && !active) RemoteColors.Warning else RemoteColors.Secondary)
                            Text("${routine.steps.size} 个步骤 · ${routine.steps.take(2).joinToString(" · ") { routineStepTitle(it, apps) }}", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton({ onRun(routine) }, enabled = active || (connected && !run.busy), modifier = Modifier.size(48.dp).testTag("routine-run-${routine.id}").semantics { stateDescription = status ?: "尚未执行" }) {
                        Icon(painterResource(drawable), "$action ${routine.name}", tint = if (!active && (!connected || run.busy)) RemoteColors.Disabled else if (drawable == R.drawable.ic_error) RemoteColors.Warning else RemoteColors.Action)
                    }
                    Box {
                        IconButton({ menu = true }, modifier = Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_more), "更多 ${routine.name}") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("最后一次执行详情") }, enabled = record != null, onClick = { menu = false; onDetails(routine) })
                            DropdownMenuItem(text = { Text("添加到桌面") }, onClick = { menu = false; onPin(routine) })
                            DropdownMenuItem(text = { Text("编辑组合") }, enabled = !run.busy, onClick = { menu = false; onEdit(routine) })
                            DropdownMenuItem(text = { Text("创建副本") }, enabled = !run.busy && routines.size < 20, onClick = { menu = false; onCopy(routine) })
                            DropdownMenuItem(text = { Text("删除组合", color = RemoteColors.Warning) }, enabled = !run.busy, onClick = { menu = false; onDelete(routine) })
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
