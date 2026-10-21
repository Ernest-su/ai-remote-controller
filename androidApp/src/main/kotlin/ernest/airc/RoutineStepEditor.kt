package ernest.airc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoutineStepEditorContent(kind: String, onKind: (String) -> Unit, value: String, onValue: (String) -> Unit,
    apps: List<InstalledApp>, enabled: Boolean, onChooseApp: () -> Unit, valid: Boolean, appIcon: @Composable (String) -> Unit, timeout: String = "30", onTimeout: (String) -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("routine-step-editor"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("这一步要做什么？", style = MaterialTheme.typography.titleMedium)
        var expanded by remember { mutableStateOf(false) }
        val kinds = listOf("app" to "打开应用", "key" to "遥控按键", "wait" to "等待",
            "wait_app" to "等待应用进入前台", "wait_text" to "等待界面文字", "wait_playing" to "等待播放开始")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val menuWidth = maxWidth
            OutlinedButton({ expanded = true }, enabled = enabled, shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("routine-kind-selector")) {
                Icon(painterResource(routineStepIcon(kind)), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(kinds.first { it.first == kind }.second, modifier = Modifier.weight(1f))
                Icon(painterResource(if (expanded) R.drawable.ic_up else R.drawable.ic_down), "选择步骤类型", Modifier.size(20.dp))
            }
            DropdownMenu(expanded && enabled, { expanded = false }, modifier = Modifier.width(menuWidth).testTag("routine-kind-menu")) {
                kinds.forEach { (type, title) ->
                    DropdownMenuItem(text = { Text(title) },
                        leadingIcon = { Icon(painterResource(routineStepIcon(type)), null, Modifier.size(20.dp)) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("routine-kind-$type").semantics { selected = kind == type },
                        onClick = { expanded = false; if (kind != type) onKind(type) })
                }
            }
        }
        when (kind) {
            "app", "wait_app" -> {
                Text(if (kind == "wait_app") "等待应用进入前台" else "在电视上打开一个应用", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                val app = apps.find { it.packageName == value }
                if (value.isNotEmpty()) ApplicationListRow(app ?: InstalledApp(value), onChooseApp, { appIcon(value) }, enabled = enabled, clickLabel = "更换应用")
                OutlinedButton(onChooseApp, shape = MaterialTheme.shapes.small, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("routine-choose-app")) {
                    Icon(painterResource(R.drawable.ic_apps), null, Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text(if (value.isEmpty()) "选择应用" else "更换应用")
                }
            }
            "key" -> {
                Text("发送一次遥控按键", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    routineKeys.chunked(3).forEach { keys ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            keys.forEach { key ->
                                val selected = key == value
                                Surface(color = if (selected) RemoteColors.ActionContainer else RemoteColors.Surface, shape = MaterialTheme.shapes.small,
                                    border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, RemoteColors.Action) else null,
                                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("routine-key-$key")
                                        .clickable(enabled = enabled, role = Role.RadioButton) { onValue(key) }
                                        .semantics { this.selected = selected }) {
                                    Box(Modifier.padding(horizontal = 8.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
                                        Text(routineKeyLabel(key), style = MaterialTheme.typography.bodyMedium, color = if (selected) RemoteColors.Action else RemoteColors.Text)
                                    }
                                }
                            }
                            repeat(3 - keys.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            "wait" -> {
                Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("给电视一点响应时间", style = MaterialTheme.typography.bodyLarge)
                        Text("适合放在打开应用之后；加载较慢时可适当延长。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                        OutlinedTextField(value, onValue, label = { Text("等待时间（秒）") }, singleLine = true, enabled = enabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = value.isNotBlank() && parseRoutineSeconds(value) == null,
                            supportingText = { Text("支持 0.1–10 秒，例如 1.5") }, modifier = Modifier.fillMaxWidth().testTag("routine-wait-seconds"))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("0.5", "1", "2", "5").forEach { seconds ->
                                FilterChip(value == seconds, { onValue(seconds) }, enabled = enabled, label = { Text("$seconds 秒") }, modifier = Modifier.heightIn(min = 48.dp))
                            }
                        }
                    }
                }
            }
        }
        if (kind == "wait_text") OutlinedTextField(value, onValue, label = { Text("界面文字或控件资源 ID") }, enabled = enabled,
            supportingText = { Text("仅检查界面树中的文字、描述与资源 ID；自绘界面可能无法读取。") }, modifier = Modifier.fillMaxWidth())
        if (kind.startsWith("wait_")) {
            OutlinedTextField(timeout, onTimeout, label = { Text("超时（秒）") }, enabled = enabled, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = timeout.toIntOrNull() !in 1..60,
                supportingText = { Text("1–60 秒；超时或无法读取状态时停止，不继续盲按。") }, modifier = Modifier.fillMaxWidth())
        }
        if (!valid && value.isNotBlank() && (kind != "wait" || parseRoutineSeconds(value) != null)) Text("步骤数或累计等待已达到上限，请缩短等待或移除已有步骤。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Warning)
    }
}
