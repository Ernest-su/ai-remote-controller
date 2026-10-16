package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
                        OutlinedButton({ onKey(key) }, enabled = connected, interactionSource = rememberRemoteKeyInteractions(connected),
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("keypad-$key"), shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
                            Text(when (key) { "delete" -> "删除"; "mute" -> "静音"; else -> key })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("channel_down" to "上一频道", "channel_up" to "下一频道").forEach { (key, title) ->
                    OutlinedButton({ onKey(key) }, enabled = connected, interactionSource = rememberRemoteKeyInteractions(connected),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("keypad-$key"), shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) { Text(title) }
                }
            }
        }
        if (connected && output.isNotBlank()) Text("已收到操作反馈，可展开查看详情。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
        OperationDetails(output)
    }
}
