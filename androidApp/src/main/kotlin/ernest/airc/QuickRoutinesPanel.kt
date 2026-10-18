package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Composable
internal fun QuickRoutinesPanel(state: RemoteUiState, model: RemoteViewModel, onClose: () -> Unit) {
    val run by model.quickRoutines.state.collectAsState()
    var route by rememberSaveable { mutableStateOf("list") }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    val saver = remember { Saver<List<RoutineStep>, String>(
        save = { Json.encodeToString(ListSerializer(RoutineStep.serializer()), it) },
        restore = { runCatching { Json.decodeFromString<List<RoutineStep>>(it) }.getOrDefault(emptyList()) }) }
    var steps by rememberSaveable(stateSaver = saver) { mutableStateOf(emptyList()) }
    var originalName by rememberSaveable { mutableStateOf("") }
    var originalSteps by rememberSaveable(stateSaver = saver) { mutableStateOf(emptyList()) }
    var stepIndex by rememberSaveable { mutableIntStateOf(-1) }
    var kind by rememberSaveable { mutableStateOf("app") }
    var value by rememberSaveable { mutableStateOf("") }
    var originalKind by rememberSaveable { mutableStateOf("app") }
    var originalValue by rememberSaveable { mutableStateOf("") }
    var discardTo by remember { mutableStateOf<String?>(null) }
    val executions by model.quickRoutines.history.collectAsState()
    val lastRuns = executions.filter { it.deviceId == state.selected }.associateBy { it.routine.id }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<QuickRoutine?>(null) }
    val device = state.selected
    var editingDevice by rememberSaveable { mutableStateOf(device) }
    LaunchedEffect(device) {
        if (editingDevice != device) { route = "list"; editing = null; name = ""; steps = emptyList(); detailsId = null; pendingDelete = null; discardTo = null; editingDevice = device }
    }
    LaunchedEffect(state.connected) {
        if (state.connected && state.apps.isEmpty() && !state.appsLoading && state.appsError == null) model.listApps()
    }
    val screen = if (route == "details" && lastRuns[detailsId] == null) "list" else route
    fun edit(routine: QuickRoutine? = null, copy: Boolean = false) {
        editing = routine?.id?.takeUnless { copy }; name = routine?.name?.let { if (copy) "$it 副本".take(40) else it }.orEmpty()
        steps = routine?.steps.orEmpty(); originalName = if (copy) "" else name; originalSteps = if (copy) emptyList() else steps; route = "edit"
    }
    fun editStep(index: Int = -1) {
        stepIndex = index; kind = steps.getOrNull(index)?.type ?: "app"; value = steps.getOrNull(index)?.let { if (it.type == "wait") routineSeconds(it.value.toLong()) else it.value }.orEmpty()
        originalKind = kind; originalValue = value; route = "step"
    }
    fun back() {
        when (screen) {
            "apps" -> route = "step"
            "step" -> if (kind != originalKind || value != originalValue) discardTo = "edit" else route = "edit"
            "edit" -> if (name != originalName || steps != originalSteps) discardTo = "list" else route = "list"
            "details" -> route = "list"
            else -> onClose()
        }
    }
    val draft = QuickRoutine(editing ?: "draft", name.trim(), steps)
    val canSave = device != null && !run.busy && draft.valid() && (state.routines.size < 20 || editing != null)
    val step = when (kind) { "wait" -> RoutineStep("wait", parseRoutineSeconds(value)?.toString().orEmpty()); else -> RoutineStep(kind, value) }
    val updatedSteps = if (stepIndex in steps.indices) steps.toMutableList().apply { this[stepIndex] = step } else steps + step
    val canSaveStep = !run.busy && step.valid() && QuickRoutine("draft", "draft", updatedSteps).valid()
    val iconRevision by model.appIcons.revision.collectAsState()
    val appIcon: @Composable (String) -> Unit = { packageName ->
        key(iconRevision) { ApplicationIcon(packageName, state.connected && "apps.icon" in state.tools, model.appIcons.cached(packageName), model.appIcons::load) }
    }
    val title = when (screen) { "edit" -> if (editing == null) "新建快捷操作" else "编辑快捷操作"; "step" -> if (stepIndex < 0) "添加步骤" else "编辑第 ${stepIndex + 1} 步"; "apps" -> "选择应用"; "details" -> "最后一次执行详情"; else -> "快捷操作" }
    LibraryDialog(title, "quick-routines-dialog", ::back,
        subtitle = state.history.find { it.id == device }?.visibleName ?: "请选择目标电视",
        actions = {
            if (screen == "list") IconButton({ edit() }, enabled = !run.busy && state.routines.size < 20 && device != null, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_add), "新建快捷操作")
            }
        }, footer = when (screen) {
            "edit" -> ({ ActionButton("保存组合", canSave) {
                model.saveRoutine(draft.copy(id = editing ?: java.util.UUID.randomUUID().toString())); route = "list"
            } })
            "step" -> ({ ActionButton(if (stepIndex < 0) "添加此步骤" else "保存步骤", canSaveStep) { steps = updatedSteps; route = "edit" } })
            else -> null
        }) {
        when (screen) {
            "edit" -> RoutineEditorContent(name, { name = it.take(40) }, steps, state.apps, !run.busy,
                { editStep() }, { editStep(it) }, { index, direction -> steps = moveRoutineStep(steps, index, direction) },
                { index -> steps = steps.toMutableList().apply { add(index + 1, steps[index]) } },
                { index -> steps = steps.filterIndexed { i, _ -> i != index } }, appIcon)
            "step" -> RoutineStepEditorContent(kind, { kind = it; value = "" }, value, { value = it }, state.apps, !run.busy,
                { route = "apps" }, canSaveStep, appIcon)
            "apps" -> ApplicationLibraryPanel(state.apps, state.shortcuts.favorites, state.connected, state.appsLoading, state.appsError,
                model::listApps, { value = it.packageName; route = "step" }, appIcon, selected = value, selectLabel = "选择应用", selectionAvailable = true)
            "details" -> lastRuns[detailsId]?.let { RoutineExecutionDetails(it, state.apps, run.routineId == detailsId && run.busy, model.quickRoutines::cancel, appIcon) }
            else -> RoutineLibraryContent(state.routines, state.apps, state.connected, run, lastRuns,
                { edit() }, { edit(it) }, { edit(it, copy = true) }, { pendingDelete = it },
                { routine ->
                    if (run.busy && run.routineId == routine.id) {
                        if (run.pauseRequested) model.quickRoutines.resume() else model.quickRoutines.pause()
                    } else model.quickRoutines.start(routine, state.connected, device, state.history.find { it.id == device }?.visibleName)
                }, { detailsId = it.id; route = "details" }, device != null)
        }
    }
    if (discardTo != null) AlertDialog(onDismissRequest = { discardTo = null }, title = { Text("放弃未保存的修改？") },
        text = { Text("返回后，本次修改不会保存。") },
        confirmButton = { TextButton({ route = checkNotNull(discardTo); discardTo = null }) { Text("放弃修改", color = RemoteColors.Warning) } },
        dismissButton = { TextButton({ discardTo = null }) { Text("继续编辑") } })
    pendingDelete?.let { routine -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除快捷操作？") },
        text = { Text("将删除“${routine.name}”，此操作无法撤销。") },
        confirmButton = { TextButton({ model.removeRoutine(routine.id); pendingDelete = null }, enabled = !run.busy) { Text("删除", color = RemoteColors.Warning) } },
        dismissButton = { TextButton({ pendingDelete = null }) { Text("取消") } }) }
}
