package ernest.airc

import androidx.compose.runtime.Composable

@Composable
internal fun TouchpadScreen(state: RemoteUiState, model: RemoteViewModel) =
    TouchpadPanel(state.connected, state.tools, state.touchBusy, state.output, model::gesture, model::key)
