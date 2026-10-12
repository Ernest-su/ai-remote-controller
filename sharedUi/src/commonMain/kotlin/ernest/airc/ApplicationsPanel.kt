package ernest.airc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApplicationsPanel(apps: List<InstalledApp>, connected: Boolean, loading: Boolean, error: String?, output: String,
    onRefresh: () -> Unit, onOpen: (String) -> Unit, onInspect: ((String) -> Unit)? = null,
    onUninstall: ((String) -> Unit)? = null, onClearCache: ((String) -> Unit)? = null,
    management: @Composable ColumnScope.() -> Unit = {}, icon: @Composable (String) -> Unit = {},
    actionsBusy: Boolean = false, operationMessage: String? = null) {
    var packageName by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("all") }
    var visibleCount by rememberSaveable { mutableStateOf(30) }
    var showPackage by rememberSaveable { mutableStateOf(false) }
    var showManagement by rememberSaveable { mutableStateOf(false) }
    var showResult by rememberSaveable { mutableStateOf(false) }
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var launchName by rememberSaveable { mutableStateOf<String?>(null) }
    val drafts = rememberSaveableStateHolder()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val enabled = connected && !loading && !actionsBusy
    val matches = filterInstalledApps(apps, query).filter {
        when (category) { "user" -> it.system == false; "system" -> it.system == true; else -> true }
    }
    fun launchApp(app: String) { launchName = app; onOpen(app) }
    fun manage(app: String, action: (String) -> Unit) { selectedPackage = null; showManagement = true; action(app) }

    if (showPackage) ManagementDialog("按包名打开", "app-package-dialog", { showPackage = false }) {
        Text("输入目标设备上的完整应用包名。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        OutlinedTextField(packageName, { packageName = it }, label = { Text("应用包名") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("app-package-input"))
        ActionButton("打开应用", enabled && packageName.isNotBlank()) { showPackage = false; launchApp(packageName.trim()) }
    }
    if (showManagement) ManagementDialog("应用信息与任务", "app-management-dialog", { showManagement = false }) { drafts.SaveableStateProvider("app-management") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { management() } } }
    if (showResult) ManagementDialog("操作结果", "app-result-dialog", { showResult = false }) { ResultPanel(output) }
    apps.firstOrNull { it.packageName == selectedPackage }?.let { app ->
        ManagementDialog(app.label ?: "应用", "app-detail-dialog", { selectedPackage = null }) {
            InfoRow("包名", app.packageName)
            InfoRow("类型", when (app.system) { true -> "系统应用"; false -> "用户应用"; null -> "类型未知" })
            ActionButton("启动应用", enabled) { selectedPackage = null; launchApp(app.packageName) }
            onInspect?.let { inspect -> ActionButton("查看应用信息", enabled) { manage(app.packageName, inspect) } }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("apps-page"), state = list, contentPadding = PaddingValues(16.dp)) {
        item(key = "tools") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("已安装应用", style = MaterialTheme.typography.titleMedium)
                        Text("${apps.size} 个应用 · 在目标设备上管理", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    }
                    ManagementIconButton(ManagementIcons.Refresh, "刷新应用列表", enabled, "apps-refresh", onRefresh)
                    Box {
                        ManagementIconButton(ManagementIcons.More, "应用管理工具", tag = "apps-tools") { menu = true }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("按包名打开") }, onClick = { menu = false; showPackage = true })
                            DropdownMenuItem(text = { Text("管理任务") }, onClick = { menu = false; showManagement = true })
                        }
                    }
                }
                OutlinedTextField(query, { query = it; visibleCount = 30 }, label = { Text("搜索名称或包名") }, singleLine = true,
                    leadingIcon = { Icon(ManagementIcons.Search, null, Modifier.size(24.dp)) },
                    trailingIcon = { if (query.isNotEmpty()) ManagementIconButton(ManagementIcons.Close, "清除应用搜索", tag = "apps-clear-search") { query = "" } },
                    modifier = Modifier.fillMaxWidth().testTag("apps-search"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("all" to "全部", "user" to "用户应用", "system" to "系统应用").forEach { (value, label) ->
                        FilterChip(category == value, { category = value; visibleCount = 30; scope.launch { list.scrollToItem(0) } },
                            label = { Text(label, style = MaterialTheme.typography.bodyMedium) }, modifier = Modifier.heightIn(min = 48.dp).testTag("apps-filter-$value"))
                    }
                }
                if (!connected) ManagementMessage("设备未连接，可浏览已有列表；连接后即可刷新和管理应用。")
                if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取应用列表…", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary) }
                error?.let { message -> ManagementMessage(message, error = true, actionLabel = if (enabled) "重新加载" else null, action = onRefresh) }
                operationMessage?.let { message -> ManagementMessage(message, actionLabel = "查看应用任务") { showManagement = true } }
                launchName?.let { name -> ManagementMessage("已请求打开 $name，可查看操作反馈。", actionLabel = "查看操作结果") { showResult = true } }
                Text("${matches.size} 个${if (query.isNotBlank()) "匹配的" else ""}应用", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                Spacer(Modifier.height(4.dp))
            }
        }
        if (matches.isEmpty() && !loading) item(key = "empty") {
            Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                ManagementEmpty(ManagementIcons.Apps,
                    if (query.isNotBlank() || category != "all") "没有匹配的应用" else if (error != null) "暂时无法读取应用" else "暂无应用列表",
                    if (query.isNotBlank() || category != "all") "试试其他关键词，或查看全部应用。" else if (connected) "刷新后显示目标设备上已安装的应用。" else "连接设备后，将在这里显示应用。",
                    if (query.isNotBlank() || category != "all") "清除筛选" else null) { query = ""; category = "all" }
            }
        }
        items(matches.take(visibleCount), key = { it.packageName }) { app ->
            ApplicationManagementRow(app, enabled, { selectedPackage = app.packageName }, { launchApp(app.packageName) },
                onInspect?.let { action -> { manage(app.packageName, action) } },
                if (app.system == false) onClearCache?.let { action -> { manage(app.packageName, action) } } else null,
                if (app.system == false) onUninstall?.let { action -> { manage(app.packageName, action) } } else null,
                { icon(app.packageName) })
        }
        if (matches.size > visibleCount) item(key = "more") {
            TextButton({ visibleCount += 30 }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("显示更多应用") }
        }
        item(key = "footer") { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ApplicationManagementRow(app: InstalledApp, enabled: Boolean, onDetail: () -> Unit, onOpen: () -> Unit,
    onInspect: (() -> Unit)?, onCache: (() -> Unit)?, onUninstall: (() -> Unit)?, icon: @Composable () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = RemoteColors.Surface) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = RemoteDimensions.ManagementRowHeight).testTag("app-row-${app.packageName}"), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "查看应用") { onDetail() }
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(RemoteDimensions.ApplicationIcon).background(RemoteColors.Fill, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                        Icon(ManagementIcons.Apps, null, Modifier.size(24.dp), tint = RemoteColors.Action)
                        icon()
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(app.label ?: app.packageName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (app.label != null) Text(app.packageName, style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when (app.system) { true -> "系统应用"; false -> "用户应用"; null -> "类型未知" }, style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    }
                }
                ManagementIconButton(ManagementIcons.Play, "启动 ${app.label ?: app.packageName}", enabled, "app-open-${app.packageName}", onOpen)
                Box {
                    ManagementIconButton(ManagementIcons.More, "更多 ${app.label ?: app.packageName}", tag = "app-more-${app.packageName}") { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("应用详情") }, onClick = { menu = false; onDetail() })
                        onInspect?.let { DropdownMenuItem(text = { Text("查看应用信息") }, enabled = enabled, onClick = { menu = false; it() }) }
                        onCache?.let { DropdownMenuItem(text = { Text("清理缓存") }, enabled = enabled, onClick = { menu = false; it() }) }
                        onUninstall?.let { DropdownMenuItem(text = { Text("卸载", color = RemoteColors.Warning) }, enabled = enabled, onClick = { menu = false; it() }) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start = 68.dp), color = RemoteColors.Divider, thickness = 0.5.dp)
        }
    }
}
