package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun RemoteComfortSettings(state: RemoteUiState, model: RemoteViewModel) {
    Panel {
        listOf(Triple("手机音量键控制电视", state.volumeKeysEnabled, model::setVolumeKeysEnabled),
            Triple("遥控时保持亮屏", state.keepAwake, model::setKeepAwake)).forEach { (title, value, change) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value, role = Role.Switch, onValueChange = change).padding(16.dp)) {
                Text(title, Modifier.weight(1f)); Switch(value, onCheckedChange = null)
            }
        }
        Text("仅在已连接的遥控或预览页面生效，离开页面或切后台后停止。", color = RemoteColors.Secondary)
    }
}

@Composable
internal fun FloatingRemoteSettings(transparency: Int, onChange: (Int) -> Unit) {
    val bounded = transparency.coerceIn(RemoteDimensions.FloatingRemoteMinTransparency, RemoteDimensions.FloatingRemoteMaxTransparency)
    Panel {
        SectionTitle("悬浮遥控")
        Row(Modifier.fillMaxWidth()) {
            Text("透明度", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("$bounded%", color = RemoteColors.Secondary)
        }
        Slider(value = bounded.toFloat(), onValueChange = { onChange(it.toInt()) },
            valueRange = RemoteDimensions.FloatingRemoteMinTransparency.toFloat()..RemoteDimensions.FloatingRemoteMaxTransparency.toFloat(),
            steps = (RemoteDimensions.FloatingRemoteMaxTransparency - RemoteDimensions.FloatingRemoteMinTransparency) / RemoteDimensions.FloatingRemoteTransparencyStep - 1,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("floating-remote-transparency").semantics { contentDescription = "悬浮遥控透明度" })
        Text("数值越大越透明。闲置5秒自动淡化，触摸恢复；拖动至边缘附近后松手可吸附。",
            style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
    }
}
