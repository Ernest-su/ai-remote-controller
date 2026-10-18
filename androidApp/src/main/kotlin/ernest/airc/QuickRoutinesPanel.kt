package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickRoutinesPanel(state: RemoteUiState, model: RemoteViewModel) {
    val run by model.quickRoutines.state.collectAsState()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    val saver = remember { Saver<List<RoutineStep>, String>(
        save = { Json.encodeToString(ListSerializer(RoutineStep.serializer()), it) },
        restore = { runCatching { Json.decodeFromString<List<RoutineStep>>(it) }.getOrDefault(emptyList()) }) }
    var steps by rememberSaveable(stateSaver = saver) { mutableStateOf(listOf<RoutineStep>()) }
    var kind by rememberSaveable { mutableStateOf("app") }
    var value by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<QuickRoutine?>(null) }
    val device = state.selected
    var editingDevice by rememberSaveable { mutableStateOf(device) }
    LaunchedEffect(device) { if (editingDevice != device) { editing = null; name = ""; steps = emptyList(); confirm = null; editingDevice = device } }
    LaunchedEffect(state.connected) { if (state.connected && state.apps.isEmpty()) model.listApps() }
    InfoRow("目标电视", state.history.find { it.id == state.selected }?.visibleName ?: "尚未选择")
    Text("按电视分别保存。只执行打开应用、普通遥控键与等待；电视布局变化可能影响结果。", color = RemoteColors.Secondary)
    confirm?.let { routine ->
        Text("即将在当前电视执行：${routine.name}")
        routine.steps.forEachIndexed { i, step -> Text("${i + 1}. ${step.description()}") }
        ActionButton("确认执行", state.connected && !run.busy) { model.quickRoutines.start(routine, state.connected); confirm = null }
        TextButton({ confirm = null }) { Text("取消执行") }
    }
    if (run.busy || run.message != null || run.error != null) {
        Text("${run.index} / ${run.total}")
        run.message?.let { Text(it) }; run.error?.let { Text(it, color = RemoteColors.Warning) }
        if (run.busy) ActionButton("停止后续步骤", true, model.quickRoutines::cancel)
    }
    state.routines.forEach { routine ->
        Text(routine.name, style = MaterialTheme.typography.titleMedium)
        FlowRow {
            TextButton({ confirm = routine }, enabled = state.connected && !run.busy) { Text("运行") }
            TextButton({ editing = routine.id; name = routine.name; steps = routine.steps }, enabled = !run.busy) { Text("编辑") }
            TextButton({ model.removeRoutine(routine.id) }, enabled = !run.busy) { Text("删除组合") }
        }
    }
    HorizontalDivider(color = RemoteColors.Divider)
    Text(if (editing == null) "新建快捷操作" else "编辑快捷操作")
    OutlinedTextField(name, { name = it.take(40) }, label = { Text("名称") }, enabled = !run.busy, modifier = Modifier.fillMaxWidth())
    steps.forEachIndexed { i, step ->
        Text("${i + 1}. ${step.description()}")
        TextButton({ steps = steps.filterIndexed { index, _ -> index != i } }, enabled = !run.busy) { Text("移除第 ${i + 1} 步") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("app" to "打开应用", "key" to "遥控按键", "wait" to "等待").forEach { (type, label) ->
            FilterChip(kind == type, { kind = type; value = "" }, label = { Text(label) }, enabled = !run.busy, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
    if (kind == "key") FlowRow {
        routineKeys.forEach { key -> FilterChip(value == key, { value = key }, label = { Text(routineKeyLabel(key)) }, enabled = !run.busy, modifier = Modifier.heightIn(min = 48.dp)) }
    } else OutlinedTextField(value, { value = it }, label = { Text(if (kind == "app") "应用包名" else "等待毫秒（100–10000）") }, enabled = !run.busy, modifier = Modifier.fillMaxWidth())
    if (kind == "app") filterInstalledApps(state.apps, value).take(8).forEach { app ->
        TextButton({ value = app.packageName }, enabled = !run.busy) { Text(app.label ?: app.packageName) }
    }
    val step = RoutineStep(kind, value.trim())
    ActionButton("添加步骤", !run.busy && steps.size < 24 && step.valid()) { steps = steps + step; value = "" }
    val routine = QuickRoutine(editing ?: java.util.UUID.randomUUID().toString(), name.trim(), steps)
    ActionButton("保存组合", !run.busy && device != null && routine.valid() && (state.routines.size < 20 || editing != null)) {
        model.saveRoutine(routine); editing = null; name = ""; steps = emptyList()
    }
    TextButton({ editing = null; name = ""; steps = emptyList() }, enabled = !run.busy) { Text("清空编辑") }
    Text("最多 20 个组合，每个最多 24 步、累计等待不超过 60 秒。停止、后台、断线或切换电视后不会自动继续。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
}
