package ernest.airc.desktop
import ernest.airc.*
import androidx.compose.runtime.*
@Composable
internal fun DesktopGamepad(state: DesktopState, controller: DesktopController) {
    val continuous by controller.virtualGamepad.state.collectAsState()
    val available = gamepadAvailable(state.connected, state.tools)
    DisposableEffect(controller) { onDispose { controller.virtualGamepad.stop() } }
    LaunchedEffect(available) { if(!available) controller.virtualGamepad.stop() }
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if(!focused) controller.virtualGamepad.stop() }
    GamepadPanel(continuous, available, { transform -> controller.virtualGamepad.change(transform) })
}
