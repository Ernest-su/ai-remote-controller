package ernest.airc.desktop
import ernest.airc.*
import androidx.compose.runtime.*
@Composable
internal fun DesktopGamepad(state: DesktopState, controller: DesktopController) {
    val continuous by controller.virtualGamepad.state.collectAsState()
    val available = state.connected && listOf("gamepad.virtual_capabilities", "gamepad.virtual_open", "gamepad.virtual_update", "gamepad.virtual_close", "gamepad.pulse", "gamepad.reset").all { it in state.tools }
    DisposableEffect(controller) { onDispose { controller.virtualGamepad.stop() } }
    LaunchedEffect(available) { if(!available) controller.virtualGamepad.stop() }
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if(!focused) controller.virtualGamepad.stop() }
    GamepadPanel(continuous, available,
        { controller.virtualGamepad.start(available, allowSynthetic = true) },
        { controller.virtualGamepad.stop() }, { transform -> controller.virtualGamepad.change(transform) })
}
