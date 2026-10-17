package ernest.airc
import androidx.compose.runtime.*
@Composable
internal fun GamepadScreen(state: RemoteUiState, model: RemoteViewModel) {
    GamepadLandscapeOrientation()
    val continuous by model.virtualGamepad.state.collectAsState()
    val available = gamepadAvailable(state.connected, state.tools)
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
    GamepadPanel(continuous, available, { transform -> model.virtualGamepad.change(continuous.type, transform) })
}
