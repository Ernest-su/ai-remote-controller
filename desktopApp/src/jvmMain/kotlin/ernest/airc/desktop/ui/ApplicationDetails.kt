package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.runtime.*

@Composable
fun DesktopApplicationDetails(packageName: String, state: DesktopState, controller: DesktopController, onRemoved: () -> Unit) {
    val control by controller.appControl.state.collectAsState()
    val cache by controller.appCache.state.collectAsState()
    val management by controller.appManagement.state.collectAsState()
    val installation by controller.appInstallation.state.collectAsState()
    val otherBusy = state.busy || state.submitting || cache.busy || management.busy || installation.busy
    val supported = "apps.control_info" in state.tools
    LaunchedEffect(packageName, state.connected, state.deviceId, supported) {
        controller.appControl.inspect(packageName, state.connected && supported)
    }
    DisposableEffect(packageName) {
        onDispose {
            controller.appControl.confirm(false)
            controller.appCache.confirm(false)
            controller.appManagement.confirm(false)
        }
    }
    AppDetailsPanel(packageName, control, state.connected, state.tools, otherBusy,
        onRefresh = { controller.appControl.inspect(packageName, state.connected && supported && !otherBusy) },
        onAction = { action, permission -> controller.appControl.prepare(action, permission, state.connected && !otherBusy, controller::listApps) },
        onConfirm = controller.appControl::confirm,
        onOpen = { controller.openApp(packageName) },
        onCache = { controller.appCache.prepare(packageName, state.connected && !otherBusy && !control.busy) },
        onUninstall = { controller.appManagement.uninstall(packageName, state.connected && !otherBusy && !control.busy) { controller.appControl.clear(); controller.listApps(); onRemoved() } },
        tasks = {
            AppCachePanel(cache, state.connected && !control.busy && !management.busy && !installation.busy, controller.appCache::confirm)
            AppManagementPanel(management, state.connected && !control.busy && !cache.busy && !installation.busy, controller.appManagement::confirm)
        })
}
