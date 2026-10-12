package ernest.airc.desktop
import ernest.airc.*
import androidx.compose.runtime.*
@Composable
internal fun DesktopGamepad(state: DesktopState, controller: DesktopController) {
    val control by controller.gamepad.state.collectAsState()
    val continuous by controller.virtualGamepad.state.collectAsState()
    val available = state.connected && listOf("capabilities","open","update","close").all { "gamepad.virtual_$it" in state.tools }
    DisposableEffect(controller) { onDispose { controller.virtualGamepad.stop() } }
    LaunchedEffect(available) { if(!available) controller.virtualGamepad.stop() }
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if(!focused) controller.virtualGamepad.stop() }
    val finite = state.connected && !state.busy && !continuous.running && !continuous.stopping
    GamepadPanel(control, finite && "gamepad.pulse" in state.tools && "gamepad.reset" in state.tools,
        { controller.gamepad.pulse(it,finite && "gamepad.pulse" in state.tools) },
        { controller.gamepad.reset(finite && "gamepad.reset" in state.tools) }) {
        VirtualGamepadPanel(continuous, available && !control.busy,
            { controller.virtualGamepad.start(available && !control.busy) },
            { controller.virtualGamepad.stop() }, { transform -> controller.virtualGamepad.change(transform) })
    }
}
