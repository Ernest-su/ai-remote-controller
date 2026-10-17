package ernest.airc

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun FileBrowserPanel(state: FileBrowserState, available: Boolean, onRefresh: () -> Unit, onParent: () -> Unit,
    onMore: () -> Unit, onEnter: (TargetFileEntry) -> Unit, onInspect: (TargetFileEntry) -> Unit,
    onRoot: ((String) -> Unit)? = null, onDelete: ((TargetFileEntry) -> Unit)? = null,
    onMedia: ((TargetFileEntry) -> Unit)? = null, onDownload: ((TargetFileEntry) -> Unit)? = null,
    onApk: ((TargetFileEntry) -> Unit)? = null, onCreateDirectory: ((String) -> Unit)? = null,
    directoryAvailable: Boolean = available, onNavigate: ((String) -> Unit)? = null,
    unavailableMessage: String = "请连接支持文件工具的目标服务。", operationMessage: String? = null,
    thumbnail: (@Composable (TargetFileEntry, Modifier) -> Unit)? = null,
    previewContent: (@Composable (TargetFileEntry) -> Unit)? = null,
    transfer: @Composable ColumnScope.(String) -> Unit = {}) {
    var previewName by rememberSaveable(state.root, state.path) { mutableStateOf<String?>(null) }
    var fullConfirmation by remember { mutableStateOf(false) }
    var query by rememberSaveable(state.root, state.path) { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("name") }
    var grid by rememberSaveable { mutableStateOf(false) }
    var categoryName by rememberSaveable(state.root,state.path) { mutableStateOf(FileCategory.ALL.name) }
    val category=FileCategory.valueOf(categoryName)
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var selectedName by rememberSaveable(state.root,state.path) { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var showDirectory by rememberSaveable(state.root, state.path) { mutableStateOf(false) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var toolSection by rememberSaveable { mutableStateOf("transfer") }
    var inspectedName by rememberSaveable(state.root, state.path) { mutableStateOf<String?>(null) }
    val drafts = rememberSaveableStateHolder()
    val enabled = available && !state.loading
    val entries = remember(state.entries, query, sort,category,showHidden) {
        state.entries.filter { it.name.contains(query.trim(), ignoreCase = true) &&
            (showHidden || !it.name.startsWith(".")) && (category==FileCategory.ALL || fileCategory(it)==category) }
            .sortedWith(compareBy<TargetFileEntry> { it.kind != "directory" }
                .thenBy { if (sort == "size") -(it.sizeBytes ?: -1) else 0L }
                .thenBy { if(sort=="type") fileCategory(it).title else "" }
                .thenBy { it.name.lowercase() }.thenBy { it.name })
    }
    val list = rememberLazyListState()
    LaunchedEffect(state.root, state.path, query, sort,category,showHidden,grid) { list.scrollToItem(0) }
    fun tools(section: String, action: () -> Unit = {}) { toolSection = section; showTools = true; action() }
    fun inspect(entry: TargetFileEntry) { inspectedName = entry.name; onInspect(entry) }

    previewName?.let { name ->
        val entry = state.entries.firstOrNull { it.name == name }
        if (entry != null && filePreviewKind(entry) != null && previewContent != null) ManagementDialog("文件预览", "files-preview-dialog", { previewName = null }) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if(filePreviewKind(entry) in listOf(FilePreviewKind.VIDEO,FilePreviewKind.AUDIO)) {
                Text("在主控设备播放", style=MaterialTheme.typography.titleMedium)
                onMedia?.let { target -> ActionButton("改为在被控设备播放", enabled) {previewName=null;tools("media") {target(entry)}} }
            }
            if (available) previewContent(entry) else ManagementMessage("设备未连接或正在执行操作，恢复后可重新打开预览。")
        }
    }
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
    val selected=state.entries.firstOrNull { it.name==selectedName }
    LaunchedEffect(state.entries) { if(selectedName!=null && selected==null) selectedName=null }
    Column(Modifier.fillMaxSize()) {
        Surface(color=RemoteColors.Surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(horizontal=8.dp)) {
                    Text("文件管理",style=MaterialTheme.typography.titleMedium)
                    Text(fileRootTitle(state.root),style=MaterialTheme.typography.bodySmall,color=RemoteColors.Secondary)
                }
                ManagementIconButton(ManagementIcons.Refresh,"刷新目录",enabled,"files-refresh",onRefresh)
                ManagementIconButton(if(grid) ManagementIcons.List else ManagementIcons.Apps,if(grid) "切换列表视图" else "切换网格视图",tag="files-view") { grid=!grid }
                ManagementIconButton(ManagementIcons.Transfer,"文件操作与任务",tag="files-tools") { showTools=true }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("files-page"), state = list, contentPadding = PaddingValues(16.dp)) {
        item(key = "tools") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                onRoot?.let { select -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    listOf("downloads", "inbox", "full").forEach { root ->
                        FilterChip(state.root == root, { if (root == "full" && state.root != "full") fullConfirmation = true else select(root) },
                            enabled = !state.loading, label = { Text(fileRootTitle(root), style = MaterialTheme.typography.bodyMedium) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("files-root-$root"))
                    }
                } }
                Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ManagementIconButton(ManagementIcons.Up, "上一级", enabled && state.path.isNotEmpty(), "files-parent", onParent)
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            val segments = state.path.split('/').filter { it.isNotEmpty() }
                            TextButton({ onNavigate?.invoke("") }, enabled = enabled && segments.isNotEmpty() && onNavigate != null,
                                colors = ButtonDefaults.textButtonColors(disabledContentColor = RemoteColors.Text),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("files-path-root")) { Text(fileRootTitle(state.root), style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false) }
                            segments.forEachIndexed { index, segment ->
                                Icon(ManagementIcons.Next, null, Modifier.height(48.dp).width(16.dp), tint = RemoteColors.Secondary)
                                TextButton({ onNavigate?.invoke(segments.take(index + 1).joinToString("/")) },
                                    enabled = enabled && index != segments.lastIndex && onNavigate != null,
                                    colors = ButtonDefaults.textButtonColors(disabledContentColor = RemoteColors.Text),
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("files-path-$index")) { Text(segment, style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false) }
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
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp),itemVerticalAlignment=Alignment.CenterVertically) {
                    Text(if (query.isBlank() && category==FileCategory.ALL && showHidden) "${state.entries.size} 项${if (state.nextCursor != null) " · 已加载" else ""}" else "${entries.size} / ${state.entries.size} 项", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    Box {
                        TextButton({filterMenu=true},Modifier.heightIn(min=48.dp).testTag("files-category")) { Text(category.title,style=MaterialTheme.typography.bodyMedium) }
                        DropdownMenu(filterMenu,{filterMenu=false}) {
                            FileCategory.entries.forEach { value -> DropdownMenuItem(text={Text(value.title)},onClick={categoryName=value.name;filterMenu=false},modifier=Modifier.testTag("files-category-${value.name.lowercase()}")) }
                            HorizontalDivider(color=RemoteColors.Divider)
                            DropdownMenuItem(text={Text(if(showHidden) "隐藏点开头文件" else "显示隐藏文件")},onClick={showHidden=!showHidden;filterMenu=false})
                        }
                    }
                    Box {
                        TextButton({ sortMenu = true }, Modifier.heightIn(min = 48.dp).testTag("files-sort")) { Text(when(sort) { "size" -> "大小优先"; "type" -> "类型排序"; else -> "名称排序" }, style = MaterialTheme.typography.bodyMedium) }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            DropdownMenuItem(text = { Text("名称 A–Z") }, onClick = { sort = "name"; sortMenu = false })
                            DropdownMenuItem(text = { Text("大小从大到小") }, onClick = { sort = "size"; sortMenu = false })
                            DropdownMenuItem(text = { Text("文件类型") }, onClick = { sort = "type"; sortMenu = false })
                        }
                    }
                }
            }
        }
        if (entries.isEmpty() && !state.loading) item(key = "empty") {
            Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                ManagementEmpty(ManagementIcons.Folder,
                    when { query.isNotBlank() || category!=FileCategory.ALL -> "没有匹配的文件"; state.error != null -> "暂时无法读取目录"; !state.loaded -> "尚未读取目录"; state.entries.isNotEmpty() -> "没有可显示的文件"; else -> "目录为空" },
                    when { query.isNotBlank() -> "试试其他关键词${if (state.nextCursor != null) "，或继续加载下一页" else ""}。"; !state.loaded -> "连接设备后刷新，即可查看此位置的文件。"; state.entries.isNotEmpty() -> "可从分类菜单显示隐藏文件，或切换为全部文件。"; else -> "可以新建目录，或从操作与任务中推送文件。" },
                    if (query.isNotBlank() || category!=FileCategory.ALL) "清除筛选" else null) { query = "";categoryName=FileCategory.ALL.name }
            }
        }
        items(if(grid) entries.chunked(2) else entries.map { listOf(it) },key={it.first().name}) { row ->
            Row(Modifier.fillMaxWidth().then(if(grid) Modifier.height(IntrinsicSize.Min) else Modifier),horizontalArrangement=Arrangement.spacedBy(if(grid) 8.dp else 0.dp)) {
                row.forEach { entry ->
                    Box(Modifier.weight(1f).then(if(grid) Modifier.fillMaxHeight() else Modifier)) {
                        FileManagementRow(entry,enabled,
                            { if(selectedName!=null) selectedName=if(selectedName==entry.name) null else entry.name else if(entry.kind=="directory") onEnter(entry) else if(entry.name.endsWith(".apk",true) && onApk != null) tools("apk") {onApk(entry)} else if(previewContent != null && filePreviewKind(entry) != null) previewName=entry.name else inspect(entry) },
                            { inspect(entry) },onDownload?.let { { tools("transfer") { it(entry) } } },
                            onMedia?.let { { tools("media") { it(entry) } } },onApk?.let { { tools("apk") { it(entry) } } },
                            onDelete?.let { { tools("transfer") { it(entry) } } },
                            grid=grid,selected=selectedName==entry.name,onSelect={selectedName=entry.name},
                            thumbnail=thumbnail, onPreview=if(previewContent != null && filePreviewKind(entry) != null) ({previewName=entry.name}) else null)
                    }
                }
                if(grid && row.size==1) Spacer(Modifier.weight(1f))
            }
            if(grid) Spacer(Modifier.height(8.dp))
        }
        if (state.nextCursor != null) item(key = "more") { ActionButton("加载下一页", enabled, onMore) }
        item(key = "footer") { Spacer(Modifier.height(16.dp)) }
        }
        Surface(color=RemoteColors.Surface,shadowElevation=0.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal=8.dp)) {
                HorizontalDivider(color=RemoteColors.Divider,thickness=0.5.dp)
                if(selected!=null) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(selected.name,Modifier.weight(1f).padding(8.dp),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
                        ManagementIconButton(ManagementIcons.Close,"取消选择",tag="files-clear-selection") {selectedName=null}
                    }
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(previewContent != null && filePreviewKind(selected) != null) TextButton({previewName=selected.name},enabled=enabled,modifier=Modifier.heightIn(min=48.dp)) {Text("在主控设备预览 / 播放")}
                        TextButton({inspect(selected)},enabled=enabled,modifier=Modifier.heightIn(min=48.dp)) {Text("属性")}
                        if(selected.kind=="file") onDownload?.let { action -> TextButton({tools("transfer"){action(selected)}},enabled=enabled,modifier=Modifier.heightIn(min=48.dp)) {Text("下载") } }
                        onDelete?.let { action -> TextButton({tools("transfer"){action(selected)}},enabled=enabled,modifier=Modifier.heightIn(min=48.dp),colors=ButtonDefaults.textButtonColors(contentColor=RemoteColors.Warning)) {Text("删除") } }
                    }
                } else FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    ManagementIconButton(ManagementIcons.Upload,"上传文件",tag="files-upload") { tools("transfer") }
                    onCreateDirectory?.let { ManagementIconButton(ManagementIcons.AddFolder,"新建目录",directoryAvailable && !state.loading,"files-create-directory-bottom") { showDirectory=true } }
                    ManagementIconButton(ManagementIcons.Storage,"存储分析",tag="files-storage") { tools("storage") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileManagementRow(
    entry: TargetFileEntry, enabled: Boolean, onOpen: () -> Unit, onInspect: () -> Unit,
    onDownload: (() -> Unit)?, onMedia: (() -> Unit)?, onApk: (() -> Unit)?, onDelete: (() -> Unit)?,
    grid: Boolean = false, selected: Boolean = false, onSelect: () -> Unit = {},
    thumbnail: (@Composable (TargetFileEntry, Modifier) -> Unit)? = null, onPreview: (() -> Unit)? = null,
) {
    var menu by remember(entry.name) { mutableStateOf(false) }
    val supported = entry.kind in listOf("directory", "file") && validTargetFileName(entry.name)
    val interaction = Modifier.combinedClickable(enabled = enabled && supported, role = Role.Button,
        onClickLabel = if (entry.kind == "directory") "进入目录" else if(onPreview != null) "预览文件" else "查看属性",
        onLongClickLabel = "选择文件", onLongClick = onSelect, onClick = onOpen)
        .semantics { this.selected = selected }
    @Composable fun FileIcon() {
        if (thumbnail != null && (filePreviewKind(entry) != null || (entry.kind=="file" && entry.name.endsWith(".apk",true)))) {
            thumbnail(entry, Modifier.size(if(grid) RemoteDimensions.FileGridThumbnail else RemoteDimensions.ManagementFileIcon))
            return
        }
        Box(Modifier.size(RemoteDimensions.ManagementFileIcon).background(RemoteColors.Fill, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
            Icon(when(fileCategory(entry)) {
                FileCategory.FOLDER -> ManagementIcons.Folder
                FileCategory.IMAGE -> ManagementIcons.Image
                FileCategory.VIDEO, FileCategory.AUDIO -> ManagementIcons.Play
                FileCategory.APK -> ManagementIcons.Apps
                else -> ManagementIcons.File
            }, null, Modifier.size(24.dp), tint = if (entry.kind == "directory") RemoteColors.Action else RemoteColors.Secondary)
        }
    }
    @Composable fun FileLabels() {
        Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(when (entry.kind) {
            "directory" -> "文件夹"
            "file" -> listOf(entry.name.substringAfterLast('.', "").uppercase().take(8),entry.sizeBytes?.let(::managementFileSize).orEmpty()).filter {it.isNotEmpty()}.joinToString(" · ").ifEmpty {"文件"}
            "symlink" -> "符号链接 · 不可访问"
            else -> "特殊文件 · 不可访问"
        }, style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
    }
    @Composable fun MoreMenu() {
        if (supported) Box {
            ManagementIconButton(ManagementIcons.More, "更多 ${entry.name}", tag = "file-more-${entry.name}") { menu = true }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("选择文件") }, onClick = { menu = false; onSelect() })
                DropdownMenuItem(text = { Text("查看属性") }, enabled = enabled, onClick = { menu = false; onInspect() })
                if (entry.kind == "file") {
                    onPreview?.let { DropdownMenuItem(text = { Text("在主控设备预览 / 播放") }, enabled = enabled, onClick = { menu = false; it() }) }
                    onDownload?.let { DropdownMenuItem(text = { Text("下载文件") }, enabled = enabled, onClick = { menu = false; it() }) }
                    if (isTargetMedia(entry.name)) onMedia?.let { DropdownMenuItem(text = { Text("在被控设备播放") }, enabled = enabled, onClick = { menu = false; it() }) }
                    if (entry.name.endsWith(".apk", true)) onApk?.let { DropdownMenuItem(text = { Text("APK 信息") }, enabled = enabled, onClick = { menu = false; it() }) }
                }
                onDelete?.let { DropdownMenuItem(text = { Text("删除", color = RemoteColors.Warning) }, enabled = enabled, onClick = { menu = false; it() }) }
            }
        }
    }
    Surface(modifier=if(grid) Modifier.fillMaxHeight() else Modifier,color = if (selected) RemoteColors.ActionContainer else RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
        Column {
            if (grid) Box(Modifier.fillMaxWidth().heightIn(min = RemoteDimensions.FileGridHeight).testTag("file-row-${entry.name}")) {
                Column(Modifier.fillMaxWidth().then(interaction).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FileIcon()
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) { FileLabels() }
                }
                Box(Modifier.align(Alignment.TopEnd)) { MoreMenu() }
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = RemoteDimensions.ManagementRowHeight).testTag("file-row-${entry.name}"), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).then(interaction).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FileIcon()
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { FileLabels() }
                    }
                    MoreMenu()
                }
                HorizontalDivider(Modifier.padding(start = 68.dp), color = RemoteColors.Divider, thickness = 0.5.dp)
            }
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
