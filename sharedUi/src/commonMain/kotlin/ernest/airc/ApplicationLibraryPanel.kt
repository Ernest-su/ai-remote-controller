package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** One lazy list for launching, favoriting and choosing an application for a routine. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApplicationLibraryPanel(apps: List<InstalledApp>, favorites: List<String>, connected: Boolean,
    loading: Boolean, error: String?, onRefresh: () -> Unit, onSelect: (InstalledApp) -> Unit,
    icon: @Composable (String) -> Unit = {}, onFavorite: ((String) -> Unit)? = null,
    recent: List<String> = emptyList(), onClearRecent: (() -> Unit)? = null,
    onMove: ((String, Int) -> Unit)? = null, selected: String? = null, selectLabel: String = "打开应用", selectionAvailable: Boolean = connected, actionsBusy: Boolean = false, onPin: ((InstalledApp) -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("all") }
    var count by rememberSaveable { mutableIntStateOf(30) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val installed = apps.map { it.packageName }.toSet()
    val sourceApps = apps + if (onFavorite != null) favorites.filterNot { it in installed }.map { InstalledApp(it) } else emptyList()
    val recentApps = recent.mapNotNull { name -> apps.find { it.packageName == name } }
    val filtered = favoriteFirstApps(filterInstalledApps(if (!sorting && category == "recent") recentApps else sourceApps, if (sorting) "" else query), favorites).filter {
        when (if (sorting) "favorites" else category) {
            "user" -> it.system == false; "system" -> it.system == true; "favorites" -> it.packageName in favorites; else -> true
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(category, query, sorting) { count = 30; list.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize().testTag("app-library-list"), state = list, contentPadding = PaddingValues(16.dp)) {
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(if (sorting) "调整收藏顺序" else if (onFavorite == null) "选择电视上的应用" else "已收藏 ${favorites.size} / 12", style = MaterialTheme.typography.titleMedium)
                        Text(if (sorting) "使用右侧箭头调整启动顺序" else if (onFavorite == null) "点选应用后返回步骤编辑" else "点应用打开，点星标收藏", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    }
                    ManagementIconButton(ManagementIcons.Refresh, "刷新应用列表", connected && !loading && !actionsBusy, "library-refresh", onRefresh)
                    if (onMove != null || onClearRecent != null) Box {
                        ManagementIconButton(ManagementIcons.More, "常用应用工具", tag = "library-tools") { menu = true }
                        DropdownMenu(menu, { menu = false }) {
                            if (onMove != null) DropdownMenuItem(text = { Text(if (sorting) "完成排序" else "调整收藏顺序") }, enabled = favorites.isNotEmpty(), onClick = { sorting = !sorting; menu = false })
                            if (onClearRecent != null) DropdownMenuItem(text = { Text("清空最近记录") }, enabled = recent.isNotEmpty(), onClick = { onClearRecent(); menu = false })
                        }
                    }
                }
                if (sorting) TextButton({ sorting = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("完成排序") }
                else {
                    OutlinedTextField(query, { query = it }, label = { Text("搜索应用") }, placeholder = { Text("名称或包名") }, singleLine = true,
                        leadingIcon = { Icon(ManagementIcons.Search, null, Modifier.size(24.dp)) },
                        trailingIcon = { if (query.isNotEmpty()) ManagementIconButton(ManagementIcons.Close, "清除应用搜索") { query = "" } },
                        modifier = Modifier.fillMaxWidth().testTag("library-search"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val categories = listOf("all" to "全部", "user" to "用户安装", "system" to "系统应用") +
                            (if (onFavorite != null) listOf("favorites" to "已收藏", "recent" to "最近启动") else emptyList())
                        categories.forEach { (value, title) ->
                            FilterChip(category == value, { category = value }, label = { Text(title) }, modifier = Modifier.heightIn(min = 48.dp).testTag("library-filter-$value"))
                        }
                    }
                }
                if (!connected) Text(if (onFavorite == null) "设备离线，可选择已缓存的应用。" else "设备离线，可管理收藏；连接后可打开应用或刷新列表。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                if (onFavorite != null && favorites.size >= 12) Text("收藏已满，取消一个收藏后可继续添加。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { message -> ManagementMessage(message, error = true, actionLabel = if (connected && !loading && !actionsBusy) "重新加载" else null, action = onRefresh) }
                Text(if (!sorting && category == "recent") "最近通过 AI 遥控启动 · ${filtered.size} 个" else "${filtered.size} 个应用${if (!sorting && onFavorite != null) " · 收藏优先" else ""}", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                Spacer(Modifier.height(4.dp))
            }
        }
        if (filtered.isEmpty() && !loading) item(key = "empty") {
            Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
                ManagementEmpty(ManagementIcons.Apps, when { sorting || category == "favorites" -> "还没有收藏应用"; category == "recent" -> "还没有启动记录"; query.isNotBlank() -> "没有匹配的应用"; else -> "暂无应用列表" },
                    when { sorting || category == "favorites" -> "在全部应用中点亮星标，即可在这里快速找到。"; query.isNotBlank() -> "试试其他名称或包名。"; else -> "连接电视后刷新应用列表。" },
                    if (sorting || category != "all" || query.isNotBlank()) "查看全部应用" else null) { sorting = false; category = "all"; query = "" }
            }
        }
        items(filtered.take(count), key = { it.packageName }) { app ->
            ApplicationListRow(app, { onSelect(app) }, { icon(app.packageName) }, enabled = selectionAvailable && !actionsBusy && !sorting && app.packageName in installed,
                clickLabel = selectLabel) {
                if (!sorting && onPin != null) ManagementIconButton(ManagementIcons.More, "添加到桌面 ${app.label ?: app.packageName}") { onPin(app) }
                if (sorting && onMove != null) {
                    val index = favorites.indexOf(app.packageName)
                    ManagementIconButton(ManagementIcons.Up, "上移 ${app.label ?: app.packageName}", index > 0) { onMove(app.packageName, -1) }
                    ManagementIconButton(LibraryIcons.Down, "下移 ${app.label ?: app.packageName}", index < favorites.lastIndex) { onMove(app.packageName, 1) }
                } else if (onFavorite != null) AppFavoriteButton(app, app.packageName in favorites, app.packageName in favorites || favorites.size < 12) { onFavorite(app.packageName) }
                else if (app.packageName == selected) Icon(LibraryIcons.Check, "已选择", Modifier.padding(12.dp).size(24.dp), tint = RemoteColors.Action)
            }
        }
        if (filtered.size > count) item(key = "more") { TextButton({ count += 30 }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("显示更多应用") } }
    }
}
