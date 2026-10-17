package ernest.airc

import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

fun gamepadAvailable(connected: Boolean, tools: Collection<String>): Boolean = connected && listOf(
    "gamepad.virtual_capabilities", "gamepad.virtual_open", "gamepad.virtual_update",
    "gamepad.virtual_close", "gamepad.pulse", "gamepad.reset",
).all { it in tools }

@Composable
fun GamepadSessionButton(state: VirtualGamepadState, available: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val busy = state.starting || state.stopping
    val label = when {
        state.stopping -> "正在停止手柄"
        state.reconnecting -> "正在重连手柄，点击停止"
        state.starting -> "正在开始手柄"
        state.running -> "停止手柄"
        else -> "开始手柄"
    }
    IconButton(onClick = { if (state.running) onStop() else onStart() },
        enabled = !busy && (state.running || available),
        modifier = Modifier.size(48.dp).testTag("gamepad-session-toggle").semantics {
            contentDescription = label
            stateDescription = state.error ?: when {
                busy || state.reconnecting -> label
                state.running -> "手柄已启动"
                !available -> "请连接支持手柄工具的服务"
                else -> "手柄未启动"
            }
        }) {
        if (busy || state.reconnecting) CircularProgressIndicator(Modifier.size(24.dp), color = RemoteColors.Brand, strokeWidth = 2.dp)
        else Icon(if (state.running) ManagementIcons.Stop else ManagementIcons.Play, null, Modifier.size(24.dp),
            tint = if (!available && !state.running) RemoteColors.Disabled else if (state.running) RemoteColors.Warning else RemoteColors.Action)
    }
}
