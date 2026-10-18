package ernest.airc

import androidx.compose.runtime.*

@Composable
internal fun DownloadPanel(state: RemoteUiState, model: RemoteViewModel) {
    val download by model.urlDownloads.state.collectAsState()
    val downloadDirectory by model.downloadDirectories.state.collectAsState()
    InfoRow("目标电视", state.history.find { it.id == state.selected }?.visibleName ?: "尚未选择")
    val available = state.connected && !state.busy && "files.list" in state.tools
    LaunchedEffect(download.url, available) { model.urlDownloads.inspect(available && "files.url_info" in state.tools) }
    UrlDownloadPanel(download, available && listOf("files.download_url", "task.progress", "task.cancel").all { it in state.tools },
        validDownloadUrl(download.url), model.urlDownloads::editUrl, model.urlDownloads::editName,
        { model.urlDownloads.start(available) }, model.urlDownloads::cancel,
        { model.urlDownloads.directory("downloads", "") }) { selected ->
        LaunchedEffect(available, downloadDirectory.root) {
            if(available && !downloadDirectory.loaded && !downloadDirectory.loading && downloadDirectory.error == null) model.downloadDirectories.refresh(true)
        }
        FileBrowserPanel(downloadDirectory, available, { model.downloadDirectories.refresh(available) },
            { model.downloadDirectories.parent(available) }, { model.downloadDirectories.more(available) },
            { model.downloadDirectories.enter(it, available) }, {},
            onRoot=model.downloadDirectories::selectRoot,
            onNavigate={ model.downloadDirectories.ancestor(it, available) },
            onSelectDirectory={ model.urlDownloads.directory(downloadDirectory.root, downloadDirectory.path); selected() })
    }
}
