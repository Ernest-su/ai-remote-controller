package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal data class LauncherShortcutDraft(
    val id: String, val device: String, val targetName: String, val kind: String,
    val value: String, val label: String, val icon: String = "default",
)
internal data class LauncherIcon(val id: String, val label: String, val resource: Int)
internal val launcherIcons = listOf(
    LauncherIcon("default", "AI 遥控", R.drawable.ic_ai_rc),
    LauncherIcon("tv", "电视", R.drawable.ic_device_tv),
    LauncherIcon("apps", "应用", R.drawable.ic_apps),
    LauncherIcon("play", "播放", R.drawable.ic_play),
    LauncherIcon("clock", "定时", R.drawable.ic_clock),
    LauncherIcon("home", "主页", R.drawable.ic_home),
)
internal fun launcherIcon(id: String) = launcherIcons.firstOrNull { it.id == id } ?: launcherIcons.first()
internal fun validLauncherName(name: String) = name.trim().isNotEmpty() && name.length <= 120 && name.none { it.isISOControl() }

@Composable
internal fun LauncherShortcutNameDialog(draft: LauncherShortcutDraft, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(draft.id) { mutableStateOf(draft.label) }
    var icon by rememberSaveable(draft.id) { mutableStateOf(launcherIcon(draft.icon).id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加到桌面") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("目标设备：${draft.targetName}", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it }, label = { Text("快捷方式名称") }, singleLine = true,
                    isError = !validLauncherName(name), supportingText = {
                        Text(if (validLauncherName(name)) "可修改默认名称，最多 120 个字" else "请输入 1–120 个字，不含换行或控制字符")
                    }, modifier = Modifier.fillMaxWidth().testTag("launcher-shortcut-name"))
                Text("快捷方式图标", style = MaterialTheme.typography.bodyLarge)
                launcherIcons.forEach { choice ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(icon == choice.id, role = Role.RadioButton, onClick = { icon = choice.id })
                        .testTag("launcher-icon-${choice.id}").padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(choice.resource), null, Modifier.size(24.dp),
                            tint = if (choice.id == "default") androidx.compose.ui.graphics.Color.Unspecified else MaterialTheme.colorScheme.onSurface)
                        Text(choice.label, Modifier.weight(1f))
                        RadioButton(icon == choice.id, onClick = null)
                    }
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(name.trim(), icon) }, enabled = validLauncherName(name),
            modifier = Modifier.heightIn(min = 48.dp).testTag("launcher-shortcut-confirm")) { Text("添加到桌面") } },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text("取消") } },
    )
}
