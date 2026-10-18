package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

@Composable
internal fun TvInputPanel(state: RemoteUiState, model: RemoteViewModel) {
    val input by model.tvInput.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    InfoRow("目标电视", state.history.find { it.id == state.selected }?.visibleName ?: "尚未选择")
    Text("先在电视上选中输入框。文字会写入电视剪贴板并粘贴，支持中文；目标应用需允许粘贴。无需配置模型。", color = RemoteColors.Secondary)
    OutlinedTextField(input.draft, model.tvInput::edit, enabled = !input.busy, label = { Text("输入到电视") },
        modifier = Modifier.fillMaxWidth().testTag("tv-input-draft"), maxLines = 5,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false))
    TextButton({ clipboard.getText()?.text?.let(model.tvInput::edit) }, enabled = !input.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("粘贴手机剪贴板") }
    ActionButton("发送到电视", state.connected && !input.busy && input.draft.isNotBlank() && input.draft.toByteArray().size <= 65536) { model.tvInput.send(state.connected) }
    Row {
        TextButton({ model.key("delete") }, enabled = state.connected && !input.busy) { Text("删除一字") }
        TextButton({ model.key("enter") }, enabled = state.connected && !input.busy) { Text("回车") }
        TextButton({ model.tvInput.edit("") }, enabled = !input.busy) { Text("清空草稿") }
    }
    if (input.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(model.tvInput::cancel) { Text("取消输入") } }
    input.message?.let { Text(it, color = RemoteColors.Secondary) }
    input.error?.let { Text(it, color = RemoteColors.Warning) }
    Text("需要可用的 ADB 连接。文字草稿仅保留在本次 App 进程中，不记录输入历史。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
}
