package ernest.airc
import androidx.compose.runtime.*
@Composable
internal fun GamepadScreen(state: RemoteUiState, model: RemoteViewModel) {
    GamepadLandscapeOrientation()
    val control by model.gamepad.state.collectAsState()
    val continuous by model.virtualGamepad.state.collectAsState()
    val available = state.connected && listOf("capabilities","open","update","close").all { "gamepad.virtual_$it" in state.tools }
    DisposableEffect(model) { onDispose { model.virtualGamepad.stop() } }
    LaunchedEffect(available) { if(!available) model.virtualGamepad.stop() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if(event == androidx.lifecycle.Lifecycle.Event.ON_STOP || event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) model.virtualGamepad.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val finite = state.connected && !state.busy && !continuous.running && !continuous.stopping
    GamepadPanel(control, finite && "gamepad.pulse" in state.tools && "gamepad.reset" in state.tools,
        { model.gamepad.pulse(it,finite && "gamepad.pulse" in state.tools) },
        { model.gamepad.reset(finite && "gamepad.reset" in state.tools) }) {
        VirtualGamepadPanel(continuous, available && !control.busy,
            { model.virtualGamepad.start(available && !control.busy) },
            { model.virtualGamepad.stop() }, { transform -> model.virtualGamepad.change(transform) })
    }
}
