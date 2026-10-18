package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal data class FileBarAction(val label: String, val icon: ImageVector, val tag: String,
    val enabled: Boolean = true, val danger: Boolean = false, val action: () -> Unit)

/** Keep actions in a single bottom row; the trailing menu opens above its bottom-right anchor. */
@Composable
internal fun FileActionBar(actions: List<FileBarAction>) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("files-action-bar")) {
        val capacity = (maxWidth / RemoteDimensions.FileActionSlot).toInt().coerceAtLeast(2)
        val overflow = if (actions.size > capacity) actions.drop(capacity - 1) else emptyList()
        val visible = if (overflow.isEmpty()) actions else actions.take(capacity - 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            visible.forEach { item ->
                IconButton(item.action, enabled = item.enabled, modifier = Modifier.size(48.dp).testTag(item.tag)) {
                    Icon(item.icon, item.label, Modifier.size(24.dp), tint = if (!item.enabled) RemoteColors.Disabled else if (item.danger) RemoteColors.Warning else RemoteColors.Action)
                }
            }
            if (overflow.isNotEmpty()) Box {
                ManagementIconButton(ManagementIcons.More, "更多文件操作", tag = "files-overflow") { expanded = true }
                DropdownMenu(expanded, { expanded = false }, modifier = Modifier.testTag("files-overflow-menu")) {
                    overflow.forEach { item ->
                        DropdownMenuItem(text = { Text(item.label) }, leadingIcon = { Icon(item.icon, null, tint = if (item.danger) RemoteColors.Warning else RemoteColors.Action) },
                            enabled = item.enabled, modifier = Modifier.heightIn(min = 48.dp).testTag(item.tag),
                            onClick = { expanded = false; item.action() })
                    }
                }
            }
        }
    }
}
