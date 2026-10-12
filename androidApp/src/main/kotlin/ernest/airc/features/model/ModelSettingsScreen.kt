package ernest.airc

import androidx.compose.runtime.Composable

@Composable
internal fun ModelSettings(state: RemoteUiState, model: RemoteViewModel) =
    ModelProfileEditor(model.profiles, state.connected && !state.busy, state.modelConfigured, model::syncModelProfile)
