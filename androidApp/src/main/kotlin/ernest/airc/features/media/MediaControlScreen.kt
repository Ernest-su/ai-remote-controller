package ernest.airc
import androidx.compose.runtime.*
@Composable
internal fun MediaControls(state: RemoteUiState, model: RemoteViewModel) {
    val control by model.mediaControl.state.collectAsState()
    val available = state.connected && "media.status" in state.tools
    MediaControlPanel(control, available, { model.mediaControl.refresh(available) }, model.mediaControl::choose, { model.mediaControl.seek(it, available && "media.seek" in state.tools) }, { model.mediaControl.customAction(it, available && "media.custom_action" in state.tools) })
}
