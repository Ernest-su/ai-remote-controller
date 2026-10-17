package ernest.airc

import androidx.compose.runtime.Composable

@Composable
internal fun KeypadScreen(state: RemoteUiState, model: RemoteViewModel) = androidx.compose.runtime.CompositionLocalProvider(LocalRemoteKeySender provides model.remoteKeys) {
    RemoteKeyLifecycle(model.remoteKeys)
    KeypadPanel(state.connected, state.output, model::key)
}
