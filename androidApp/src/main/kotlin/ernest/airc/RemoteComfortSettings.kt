package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
