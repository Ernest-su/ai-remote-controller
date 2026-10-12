package ernest.airc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileBrowserPanel(state: FileBrowserState, available: Boolean, onRefresh: () -> Unit, onParent: () -> Unit,
    onMore: () -> Unit, onEnter: (TargetFileEntry) -> Unit, onInspect: (TargetFileEntry) -> Unit,
    onRoot: ((String) -> Unit)? = null, onDelete: ((TargetFileEntry) -> Unit)? = null,
    onMedia: ((TargetFileEntry) -> Unit)? = null, onDownload: ((TargetFileEntry) -> Unit)? = null,
    onApk: ((TargetFileEntry) -> Unit)? = null, onCreateDirectory: ((String) -> Unit)? = null,
    directoryAvailable: Boolean = available, onNavigate: ((String) -> Unit)? = null,
    unavailableMessage: String = "请连接支持文件工具的目标服务。", operationMessage: String? = null,
    transfer: @Composable ColumnScope.(String) -> Unit = {}) {
    var fullConfirmation by remember { mutableStateOf(false) }
    var query by rememberSaveable(state.root, state.path) { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("name") }
    var sortMenu by remember { mutableStateOf(false) }
    var showDirectory by rememberSaveable(state.root, state.path) { mutableStateOf(false) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var toolSection by rememberSaveable { mutableStateOf("transfer") }
    var inspectedName by rememberSaveable(state.root, state.path) { mutableStateOf<String?>(null) }
    val drafts = rememberSaveableStateHolder()
    val enabled = available && !state.loading
    val entries = remember(state.entries, query, sort) {
        state.entries.filter { it.name.contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareBy<TargetFileEntry> { it.kind != "directory" }
                .thenBy { if (sort == "size") -(it.sizeBytes ?: -1) else 0L }
                .thenBy { it.name.lowercase() }.thenBy { it.name })
    }
    val list = rememberLazyListState()
    LaunchedEffect(state.root, state.path, query, sort) { list.scrollToItem(0) }
    fun tools(section: String, action: () -> Unit = {}) { toolSection = section; showTools = true; action() }
    fun inspect(entry: TargetFileEntry) { inspectedName = entry.name; onInspect(entry) }

    if (fullConfirmation) AlertDialog(onDismissRequest = { fullConfirmation = false }, title = { Text("进入全盘浏览") },
        text = { Text("从 / 浏览目标文件系统。可访问和修改的范围取决于服务当前权限，系统保护目录可能拒绝访问。删除目录不可恢复，请逐项核对目标。") },
        confirmButton = { TextButton({ fullConfirmation = false; onRoot?.invoke("full") }, Modifier.heightIn(min = 48.dp)) { Text("进入") } },
        dismissButton = { TextButton({ fullConfirmation = false }, Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    if (showDirectory) ManagementDialog("新建目录", "files-directory-dialog", { showDirectory = false }) {
        Text("创建位置：${fileRootTitle(state.root)}/${state.path}", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        drafts.SaveableStateProvider("directory:${state.root}/${state.path}") {
            onCreateDirectory?.let { create -> FileDirectoryForm(state.root, state.path, directoryAvailable && !state.loading) { create(it); showDirectory = false } }
        }
    }
    if (showTools) ManagementDialog("文件操作与任务", "files-tools-dialog", { showTools = false }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("transfer" to "传输", "media" to "媒体", "apk" to "APK 安装", "storage" to "存储分析").forEach { (value, label) ->
                FilterChip(toolSection == value, { toolSection = value }, label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("files-tool-$value"))
            }
        }
        Text("关闭此窗口后，任务仍会继续。可从页面的操作与任务入口返回。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
        drafts.SaveableStateProvider("tools:$toolSection") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { transfer(toolSection) } }
    }
    inspectedName?.let { name ->
        ManagementDialog("文件属性", "files-properties-dialog", { inspectedName = null }) {
            InfoRow("名称", name)
            InfoRow("位置", "${fileRootTitle(state.root)}/${listOf(state.path, name).filter { it.isNotEmpty() }.joinToString("/")}")
            val selected = state.selected?.takeIf { it.root == state.root && it.path == listOf(state.path, name).filter { it.isNotEmpty() }.joinToString("/") }
            if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取属性…") }
            selected?.let { file ->
                InfoRow("类型", if (file.kind == "directory") "目录" else "文件")
                file.sizeBytes?.let { InfoRow("大小", "${managementFileSize(it)}（$it 字节）") }
            }
            state.error?.let { ManagementMessage(it, error = true) }
            if (selected == null && !state.loading) ActionButton("重新读取属性", enabled) { state.entries.firstOrNull { it.name == name }?.let(onInspect) }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("files-page"), state = list, contentPadding = PaddingValues(16.dp)) {
        item(key = "tools") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("文件管理", style = MaterialTheme.typography.titleMedium)
                        Text("浏览与管理目标设备上的文件", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    }
                    ManagementIconButton(ManagementIcons.Refresh, "刷新目录", enabled, "files-refresh", onRefresh)
                    ManagementIconButton(ManagementIcons.Transfer, "文件操作与任务", tag = "files-tools") { showTools = true }
                }
                onRoot?.let { select -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("downloads", "inbox", "full").forEach { root ->
                        FilterChip(state.root == root, { if (root == "full" && state.root != "full") fullConfirmation = true else select(root) },
                            enabled = !state.loading, label = { Text(fileRootTitle(root), style = MaterialTheme.typography.bodyMedium) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("files-root-$root"))
                    }
                } }
                Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ManagementIconButton(ManagementIcons.Up, "上一级", enabled && state.path.isNotEmpty(), "files-parent", onParent)
                        FlowRow(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            val segments = state.path.split('/').filter { it.isNotEmpty() }
                            TextButton({ onNavigate?.invoke("") }, enabled = enabled && segments.isNotEmpty() && onNavigate != null,
                                colors = ButtonDefaults.textButtonColors(disabledContentColor = RemoteColors.Text),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("files-path-root")) { Text(fileRootTitle(state.root), style = MaterialTheme.typography.bodyMedium) }
                            segments.forEachIndexed { index, segment ->
                                Icon(ManagementIcons.Next, null, Modifier.height(48.dp).width(16.dp), tint = RemoteColors.Secondary)
                                TextButton({ onNavigate?.invoke(segments.take(index + 1).joinToString("/")) },
                                    enabled = enabled && index != segments.lastIndex && onNavigate != null,
                                    colors = ButtonDefaults.textButtonColors(disabledContentColor = RemoteColors.Text),
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("files-path-$index")) { Text(segment, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                        onCreateDirectory?.let { ManagementIconButton(ManagementIcons.AddFolder, "新建目录", directoryAvailable && !state.loading, "files-create-directory") { showDirectory = true } }
                    }
                }
                OutlinedTextField(query, { query = it }, label = { Text("搜索当前目录") }, singleLine = true,
                    leadingIcon = { Icon(ManagementIcons.Search, null, Modifier.size(24.dp)) },
                    trailingIcon = { if (query.isNotEmpty()) ManagementIconButton(ManagementIcons.Close, "清除文件搜索", tag = "files-clear-search") { query = "" } },
                    modifier = Modifier.fillMaxWidth().testTag("files-search"))
                if (!available) ManagementMessage(unavailableMessage)
                if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取…", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary) }
                state.error?.let { ManagementMessage(it, error = true, actionLabel = if (enabled) "重新加载" else null, action = onRefresh) }
                operationMessage?.let { ManagementMessage(it, actionLabel = "查看文件任务") { showTools = true } }
                if (state.nextCursor != null) Text("仅搜索和排序已加载项目，加载下一页可查看更多。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (query.isBlank()) "${state.entries.size} 项${if (state.nextCursor != null) " · 已加载" else ""}" else "${entries.size} 项匹配", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    Box {
                        TextButton({ sortMenu = true }, Modifier.heightIn(min = 48.dp).testTag("files-sort")) { Text(if (sort == "size") "大小优先" else "名称排序", style = MaterialTheme.typography.bodyMedium) }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            DropdownMenuItem(text = { Text("名称 A–Z") }, onClick = { sort = "name"; sortMenu = false })
                            DropdownMenuItem(text = { Text("大小从大到小") }, onClick = { sort = "size"; sortMenu = false })
                        }
                    }
                }
            }
        }
        if (entries.isEmpty() && !state.loading) item(key = "empty") {
            Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                ManagementEmpty(ManagementIcons.Folder,
                    when { query.isNotBlank() -> "没有匹配的文件"; state.error != null -> "暂时无法读取目录"; !state.loaded -> "尚未读取目录"; else -> "目录为空" },
                    when { query.isNotBlank() -> "试试其他关键词${if (state.nextCursor != null) "，或继续加载下一页" else ""}。"; !state.loaded -> "连接设备后刷新，即可查看此位置的文件。"; else -> "可以新建目录，或从操作与任务中推送文件。" },
                    if (query.isNotBlank()) "清除搜索" else null) { query = "" }
            }
        }
        items(entries, key = { it.name }) { entry ->
            FileManagementRow(entry, enabled, { if (entry.kind == "directory") onEnter(entry) else inspect(entry) }, { inspect(entry) },
                onDownload?.let { { tools("transfer") { it(entry) } } }, onMedia?.let { { tools("media") { it(entry) } } },
                onApk?.let { { tools("apk") { it(entry) } } }, onDelete?.let { { tools("transfer") { it(entry) } } })
        }
        if (state.nextCursor != null) item(key = "more") { ActionButton("加载下一页", enabled, onMore) }
        item(key = "footer") { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun FileManagementRow(entry: TargetFileEntry, enabled: Boolean, onOpen: () -> Unit, onInspect: () -> Unit,
    onDownload: (() -> Unit)?, onMedia: (() -> Unit)?, onApk: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    val supported = entry.kind in listOf("directory", "file") && validTargetFileName(entry.name)
    Surface(color = RemoteColors.Surface) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = RemoteDimensions.ManagementRowHeight).testTag("file-row-${entry.name}"), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(enabled = enabled && supported, role = Role.Button,
                    onClickLabel = if (entry.kind == "directory") "进入目录" else "查看属性", onClick = onOpen)
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(RemoteDimensions.ManagementFileIcon).background(RemoteColors.Fill, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                        Icon(if (entry.kind == "directory") ManagementIcons.Folder else ManagementIcons.File, null, Modifier.size(24.dp),
                            tint = if (entry.kind == "directory") RemoteColors.Action else RemoteColors.Secondary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(when (entry.kind) { "directory" -> "文件夹"; "file" -> entry.sizeBytes?.let(::managementFileSize) ?: "文件"; "symlink" -> "符号链接 · 不可访问"; else -> "特殊文件 · 不可访问" },
                            style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                    }
                }
                if (supported) Box {
                    ManagementIconButton(ManagementIcons.More, "更多 ${entry.name}", tag = "file-more-${entry.name}") { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("查看属性") }, enabled = enabled, onClick = { menu = false; onInspect() })
                        if (entry.kind == "file") {
                            onDownload?.let { DropdownMenuItem(text = { Text("下载文件") }, enabled = enabled, onClick = { menu = false; it() }) }
                            if (isTargetMedia(entry.name)) onMedia?.let { DropdownMenuItem(text = { Text("媒体打开") }, enabled = enabled, onClick = { menu = false; it() }) }
                            if (entry.name.endsWith(".apk", true)) onApk?.let { DropdownMenuItem(text = { Text("APK 信息") }, enabled = enabled, onClick = { menu = false; it() }) }
                        }
                        onDelete?.let { DropdownMenuItem(text = { Text("删除", color = RemoteColors.Warning) }, enabled = enabled, onClick = { menu = false; it() }) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start = 68.dp), color = RemoteColors.Divider, thickness = 0.5.dp)
        }
    }
}

internal fun managementFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var size = bytes.toDouble() / 1024
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) { size /= 1024; unit++ }
    val tenths = (size * 10).toLong()
    return "${tenths / 10}.${tenths % 10} ${units[unit]}"
}
