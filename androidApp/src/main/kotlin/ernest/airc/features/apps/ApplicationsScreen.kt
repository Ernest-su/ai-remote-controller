package ernest.airc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
internal fun Applications(state: RemoteUiState, model: RemoteViewModel, onDetails: ((String) -> Unit)? = null) {
    androidx.compose.runtime.LaunchedEffect(state.connected) {
        if (state.connected && state.apps.isEmpty() && !state.appsLoading && state.appsError == null) model.listApps()
    }
    val iconRevision by model.appIcons.revision.collectAsState()
    val installation by model.appInstallation.state.collectAsState()
    val management by model.appManagement.state.collectAsState()
    val cache by model.appCache.state.collectAsState()
    val control by model.appControl.state.collectAsState()
    ApplicationsPanel(state.apps, state.connected, state.appsLoading, state.appsError, state.output,
        model::listApps, model::openApp,
        onDetails = onDetails,
        favorites = state.shortcuts.favorites,
        onFavorite = { name -> model.updateShortcuts { it.toggle(name) } },
        actionsBusy = control.busy || state.busy || state.submitting || management.busy || installation.busy || cache.busy,
        operationMessage = when {
            management.proposal != null || cache.proposal != null || installation.proposal != null -> "有应用操作等待确认"
            management.busy || installation.busy || cache.busy -> "应用任务正在进行，可查看进度"
            management.error != null || cache.error != null || installation.error != null -> "应用任务遇到问题，请查看详情"
            management.message != null || cache.message != null || installation.message != null -> "有应用任务结果可查看"
            else -> null
        },
        onUninstall = { model.appManagement.uninstall(it, state.connected, model::listApps) },
        onClearCache = if("apps.clear_cache" in state.tools) { { model.appCache.prepare(it,state.connected && !management.busy && !installation.busy) } } else null,
        icon = { name -> androidx.compose.runtime.key(iconRevision) { ApplicationIcon(name, state.connected && "apps.icon" in state.tools, model.appIcons.cached(name), model.appIcons::load) } },
        management = {
            if("apps.clear_cache" in state.tools) AppCachePanel(cache,state.connected && !management.busy && !installation.busy,model.appCache::confirm)
            AppManagementPanel(management, state.connected && !cache.busy && !installation.busy, model.appManagement::confirm)
            AppInstallationPanel(installation, null, state.connected && !management.busy && !cache.busy,
                {}, model.appInstallation::confirm, model.appInstallation::cancel)
        })
}
