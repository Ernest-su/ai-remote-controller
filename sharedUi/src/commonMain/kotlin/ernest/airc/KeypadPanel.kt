package ernest.airc

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun KeypadPanel(connected: Boolean, output: String, onKey: (String) -> Unit) {
    PageContent(Modifier.testTag("keypad-page")) {
        Panel {
            SectionTitle("数字键盘", "数字与频道按键发送到当前目标应用；应用需支持相应按键。")
            if (!connected) Text("请先点击右上角连接设备。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("delete", "0", "mute")).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { key ->
                        KeypadKey(key, when (key) { "delete" -> "删除"; "mute" -> "静音"; else -> key },
                            connected, Modifier.weight(1f), onKey)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("channel_down" to "上一频道", "channel_up" to "下一频道").forEach { (key, title) ->
                    KeypadKey(key, title, connected, Modifier.weight(1f), onKey)
                }
            }
        }
        if (connected && output.isNotBlank()) Text("已收到操作反馈，可展开查看详情。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
        OperationDetails(output)
    }
}

@Composable
private fun KeypadKey(key: String, label: String, enabled: Boolean, modifier: Modifier, onKey: (String) -> Unit) {
    Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small,
        border = BorderStroke(0.5.dp, RemoteColors.Divider),
        modifier = modifier.heightIn(min = 56.dp).testTag("keypad-$key").remoteKeyInput(key, enabled) { onKey(key) }) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (enabled) RemoteColors.Text else RemoteColors.Disabled)
        }
    }
}
