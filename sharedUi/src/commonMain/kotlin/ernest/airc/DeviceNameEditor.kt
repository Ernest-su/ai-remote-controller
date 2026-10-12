package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DeviceNameEditor(id: String, name: String, enabled: Boolean, onSave: (String) -> Unit) {
    var editing by rememberSaveable(id) { mutableStateOf(false) }
    var draft by rememberSaveable(id, name) { mutableStateOf(name) }
    TextButton({ draft = name; editing = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("修改显示名") }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("设备显示名") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("仅保存在本应用，不修改目标设备系统名称。")
            OutlinedTextField(draft, { draft = it }, singleLine = true, label = { Text("显示名") }, modifier = Modifier.fillMaxWidth(),
                isError = runCatching { validatedDeviceName(draft) }.isFailure,
                supportingText = { Text("1–64 个字符，不能包含控制字符") })
        } },
        confirmButton = { TextButton({ onSave(draft); editing = false }, enabled = enabled && runCatching { validatedDeviceName(draft) }.isSuccess, modifier = Modifier.heightIn(min = 48.dp)) { Text("保存") } },
        dismissButton = { TextButton({ editing = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } },
    )
}
