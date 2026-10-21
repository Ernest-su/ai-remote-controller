package ernest.airc

import androidx.compose.runtime.*

@Composable
internal fun AppShortcutsPanel(state: RemoteUiState, model: RemoteViewModel) {
    LaunchedEffect(state.connected) { if (state.connected && state.apps.isEmpty() && !state.appsLoading && state.appsError == null) model.listApps() }
    val iconRevision by model.appIcons.revision.collectAsState()
    key(state.selected) {
        ApplicationLibraryPanel(state.apps, state.shortcuts.favorites, state.connected,
            state.appsLoading, state.appsError, model::listApps, { model.openApp(it.packageName) },
            icon = { name -> key(iconRevision) { ApplicationIcon(name, state.connected && "apps.icon" in state.tools, model.appIcons.cached(name), model.appIcons::load) } },
            onFavorite = { name -> model.updateShortcuts { it.toggle(name) } }, recent = state.shortcuts.recent,
            onClearRecent = { model.updateShortcuts { it.copy(recent = emptyList()) } },
            onMove = { name, direction -> model.updateShortcuts { it.move(name, direction) } }, actionsBusy = state.busy, onPin = { model.pinLauncher("app", it.packageName, it.label ?: it.packageName) })
    }
}
