package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun BatchDownloadChoiceDialog(count: Int, enabled: Boolean, onConfirm: (BatchDownloadMode) -> Unit, onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(BatchDownloadMode.ZIP.name) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择下载方式") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("已选择 $count 个文件")
            BatchDownloadMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected == mode.name, role = Role.RadioButton, onClick = { selected = mode.name })
                    .testTag("batch-mode-${mode.name.lowercase()}").padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (mode == BatchDownloadMode.ZIP) ManagementIcons.Transfer else ManagementIcons.File, null, Modifier.size(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(mode.title, style = MaterialTheme.typography.bodyLarge)
                        Text(if (mode == BatchDownloadMode.ZIP) "保存为一个压缩文件" else "保留各自文件名，选择文件夹分别保存", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    }
                    RadioButton(selected == mode.name, onClick = null)
                }
            }
        } },
        confirmButton = { TextButton({ onConfirm(BatchDownloadMode.valueOf(selected)) }, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).testTag("batch-mode-confirm")) { Text("开始下载") } },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}
