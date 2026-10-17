package ernest.airc

import ernest.airc.*
import androidx.compose.runtime.*

@Composable
fun ApplicationDetails(packageName: String, state: RemoteUiState, model: RemoteViewModel, onRemoved: () -> Unit) {
    val control by model.appControl.state.collectAsState()
    val cache by model.appCache.state.collectAsState()
    val management by model.appManagement.state.collectAsState()
    val installation by model.appInstallation.state.collectAsState()
    val otherBusy = state.busy || state.submitting || cache.busy || management.busy || installation.busy
    val supported = "apps.control_info" in state.tools
    LaunchedEffect(packageName, state.connected, state.selected, supported) {
        model.appControl.inspect(packageName, state.connected && supported)
    }
    DisposableEffect(packageName) {
        onDispose {
            model.appControl.confirm(false)
            model.appCache.confirm(false)
            model.appManagement.confirm(false)
        }
    }
    AppDetailsPanel(packageName, control, state.connected, state.tools, otherBusy,
        onRefresh = { model.appControl.inspect(packageName, state.connected && supported && !otherBusy) },
        onAction = { action, permission -> model.appControl.prepare(action, permission, state.connected && !otherBusy, model::listApps) },
        onConfirm = model.appControl::confirm,
        onOpen = { model.openApp(packageName) },
        onCache = { model.appCache.prepare(packageName, state.connected && !otherBusy && !control.busy) },
        onUninstall = { model.appManagement.uninstall(packageName, state.connected && !otherBusy && !control.busy) { model.appControl.clear(); model.listApps(); onRemoved() } },
        tasks = {
            AppCachePanel(cache, state.connected && !control.busy && !management.busy && !installation.busy, model.appCache::confirm)
            AppManagementPanel(management, state.connected && !control.busy && !cache.busy && !installation.busy, model.appManagement::confirm)
        })
}
