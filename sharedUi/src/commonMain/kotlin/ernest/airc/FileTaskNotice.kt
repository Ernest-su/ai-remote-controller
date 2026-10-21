package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun FileTaskNotice(message: String, onView: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("files-task-notice"), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary,
            modifier = Modifier.weight(1f, fill = false).testTag("files-task-message"))
        TextButton(onView, modifier = Modifier.heightIn(min = 48.dp).testTag("files-task-view"),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(ManagementIcons.List, null, Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
            Text("查看", style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onDismiss, modifier = Modifier.size(48.dp).testTag("files-task-dismiss")) {
            Icon(ManagementIcons.Close, "关闭文件任务提示", Modifier.size(20.dp), tint = RemoteColors.Secondary)
        }
    }
}
