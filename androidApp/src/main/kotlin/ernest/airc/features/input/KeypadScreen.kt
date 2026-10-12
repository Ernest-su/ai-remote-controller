package ernest.airc

import androidx.compose.runtime.Composable

@Composable
internal fun KeypadScreen(state: RemoteUiState, model: RemoteViewModel) = KeypadPanel(state.connected, state.output, model::key)
