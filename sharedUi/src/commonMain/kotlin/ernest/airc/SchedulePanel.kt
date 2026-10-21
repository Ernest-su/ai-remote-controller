package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SchedulePanel(connected: Boolean, state: ScheduleState, apps: List<InstalledApp>, loadApps: () -> Unit, refresh: () -> Unit,
    save: (String, String?, String, String, String?, Int?) -> Unit, delete: (String, String?) -> Unit, enable: (String, Boolean, String?) -> Unit, targetName: String = "当前被控设备") {
    var creating by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf("delay") }
    var action by rememberSaveable { mutableStateOf("pause") }
    var volumeText by rememberSaveable { mutableStateOf("50") }
    val volumePercent = volumeText.toIntOrNull()?.takeIf { it in 0..100 }
    var delayValue by rememberSaveable { mutableStateOf("1800") }
    var onceTime by rememberSaveable { mutableStateOf("") }
    var dailyTime by rememberSaveable { mutableStateOf("") }
    var picker by rememberSaveable { mutableStateOf("") }
    var targetElapsedSeconds by remember(state.data?.targetNow, state.sessionKey) { mutableLongStateOf(0) }
    LaunchedEffect(state.data?.targetNow, state.sessionKey) {
        val received = kotlin.time.TimeSource.Monotonic.markNow()
        while (isActive) { delay(1000); targetElapsedSeconds = received.elapsedNow().inWholeSeconds }
    }
    val onceValue = nextScheduleOnceTime(onceTime, state.data?.targetLocalTime.orEmpty(), targetElapsedSeconds + (state.data?.targetNow?.rem(60) ?: 0))
    val value = when (kind) { "delay" -> delayValue; "daily" -> dailyTime; else -> onceValue.orEmpty() }
    if (picker == "time") ScheduleTimePicker((if (kind == "daily") dailyTime else onceTime).ifEmpty { state.data?.targetLocalTime?.drop(11)?.take(5).orEmpty() },
        { if (kind == "daily") dailyTime = it else onceTime = it; picker = "" }, { picker = "" })
    var packageName by rememberSaveable { mutableStateOf("") }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmationText by remember { mutableStateOf("") }
    LaunchedEffect(state.sessionKey) { confirmation = null }
    LaunchedEffect(connected, state.sessionKey) { if (connected) { refresh(); loadApps(); while (isActive) { delay(10000); refresh() } } }
    if (choosing) {
        LibraryDialog("选择定时操作的应用", "schedule-app-picker", { choosing = false }) {
            ApplicationLibraryPanel(if (action == "stop_app") apps.filter { it.system == false } else apps, emptyList(), connected,
                false, null, loadApps, { packageName = it.packageName; choosing = false }, selectionAvailable = true)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("schedule-page"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$targetName · 由被控端执行", style = MaterialTheme.typography.titleMedium)
                Text("任务保存在被控设备。手机遥控器退出不会影响定时执行。", style = MaterialTheme.typography.bodyMedium)
                Text("由于运行限制，每天重复的任务需要在被控设备开机后，手机遥控器连接过被控设备，或被控端遥控服务仍在后台运行。服务停止或设备关机期间无法执行；恢复后跳过错过的任务，不会补执行或自动开机。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                Text("时间以被控端本地时间为准；不能保证唤醒休眠设备。退出应用会强行停止，未保存内容可能丢失；关机需要 root 与系统接口支持。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                state.data?.let { Text("被控端时间：${it.targetLocalTime}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (!connected) Text("设备离线，连接后可管理被控端任务。", color = RemoteColors.Secondary)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { ManagementMessage(it, error = true) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ creating = !creating }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (creating) "收起新建任务" else "新建定时操作") }
            OutlinedButton(refresh, enabled = connected && !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新") }
        }
        if (creating) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("delay" to "倒计时", "once" to "单次", "daily" to "每天固定时间").forEach { (type, title) ->
                    FilterChip(kind == type, { kind = type }, label = { Text(title) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            if (kind == "delay") {
                ScheduleDropdown("倒计时时间", delayValue, listOf("900" to "15 分钟", "1800" to "30 分钟", "3600" to "60 分钟 / 1 小时", "5400" to "90 分钟", "7200" to "2 小时", "10800" to "3 小时"),
                    "schedule-delay", { delayValue = it })
            } else {
                val time = if (kind == "daily") dailyTime else onceTime
                OutlinedButton({ picker = "time" }, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("schedule-time")) {
                    Text(if (time.isEmpty()) "选择执行时间" else (if (kind == "daily") "每天执行时间：" else "执行时间：") + time)
                }
                if (kind == "once") Text(onceValue?.let { "下次执行：$it" } ?: "选择未来的执行时间；今天已过则在明天执行。", style = MaterialTheme.typography.bodyMedium)
                Text("请选择被控端的本地时间", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
            }
            ScheduleDropdown("执行动作", action, listOf("pause", "set_volume", "mute", "unmute", "shutdown", "stop_app", "open_app").map { it to scheduledActionLabel(it) },
                "schedule-action", { if (action != it) { action = it; packageName = "" } })
            if (action == "set_volume") {
                OutlinedTextField(volumeText, { volumeText = it }, label = { Text("媒体音量（0–100%）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    isError = volumePercent == null, supportingText = { Text(if (volumePercent == null) "请输入 0 到 100 的整数" else "按设备支持的音量档位取最接近值；静音状态遵循设备系统行为。") },
                    modifier = Modifier.fillMaxWidth().testTag("schedule-volume"))
            }
            if (action in listOf("mute", "unmute")) Text("仅调整被控设备的媒体静音状态。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
            val needsApp = action in listOf("stop_app", "open_app")
            if (needsApp) OutlinedButton({ choosing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(apps.find { it.packageName == packageName }?.label ?: packageName.ifEmpty { "选择应用" })
            }
            val validTime = if (kind == "delay") value.toLongOrNull() in 60L..604800L else if (kind == "daily") Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(value)
                else Regex("20[0-9]{2}-[0-9]{2}-[0-9]{2} ([01][0-9]|2[0-3]):[0-5][0-9]").matches(value)
            if (value.isNotEmpty() && !validTime) Text("请选择有效时间。", color = RemoteColors.Warning)
            Button({
                confirmationText = "将在 $targetName${if (kind == "delay") " ${value.toLong() / 60} 分钟后" else if (kind == "daily") "每天 $value" else " $value"}执行${scheduledActionLabel(action, volumePercent.takeIf { action == "set_volume" })}${if (needsApp) "：$packageName" else ""}。${if (action == "shutdown") "关机会中断当前使用。" else if (action == "stop_app") "强行停止可能丢失未保存内容。" else ""}手机退出后仍会执行。"
                val a=action; val p=packageName.takeIf { needsApp }; val k=kind; val v=value; val session=state.sessionKey; val volume=volumePercent.takeIf { a == "set_volume" }
                confirmation = { save(a, p, k, v, session, volume); creating = false }
            }, enabled = connected && !state.busy && validTime && (action != "set_volume" || volumePercent != null) && (!needsApp || packageName.isNotEmpty()), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存定时任务") }
        }
        state.data?.tasks?.forEach { task ->
            Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(scheduledActionLabel(task.action, task.volumePercent) + (task.packageName?.let { name -> " · ${apps.find { it.packageName == name }?.label ?: name}" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                    Text(if (task.kind == "daily") "每天 ${task.dailyTime} · 下次 ${task.nextLocalTime}" else "${task.nextLocalTime} · 单次", style = MaterialTheme.typography.bodyMedium)
                    Text((if (task.enabled) "已启用" else "未启用") + " · " + scheduledStatusLabel(task.lastStatus), color = RemoteColors.Secondary)
                    task.lastResult?.let { result -> var expanded by remember { mutableStateOf(false) }; TextButton({ expanded = !expanded }) { Text(if (expanded) "收起执行详情" else "执行详情") }; if (expanded) Text(result, style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({
                            confirmationText = "${if (task.enabled) "暂停" else "启用"}此定时任务：${scheduledActionLabel(task.action, task.volumePercent)} ${task.packageName.orEmpty()}？"
                            val session=state.sessionKey; confirmation = { enable(task.id, !task.enabled, session) }
                        }, enabled = connected && !state.busy && task.lastStatus != "running", modifier = Modifier.heightIn(min = 48.dp)) { Text(if (task.enabled) "暂停任务" else "启用任务") }
                        TextButton({ confirmationText = "删除此定时任务？已提交的动作不会撤销。"; val session=state.sessionKey; confirmation = { delete(task.id, session) } }, enabled = connected && !state.busy && task.lastStatus != "running", modifier = Modifier.heightIn(min = 48.dp)) { Text("删除", color = RemoteColors.Warning) }
                    }
                }
            }
        }
        if (state.data?.tasks?.isEmpty() == true) Text("还没有定时任务", color = RemoteColors.Secondary)
    }
    confirmation?.let { submit -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("确认定时操作") },
        text = { Text(confirmationText, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ confirmation = null; submit() }, enabled = connected && !state.busy) { Text("确认") } },
        dismissButton = { TextButton({ confirmation = null }) { Text("取消") } }) }
}
