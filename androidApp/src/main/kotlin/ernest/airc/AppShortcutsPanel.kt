package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppShortcutsPanel(state: RemoteUiState, model: RemoteViewModel) {
    var manage by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(state.connected) { if (state.connected && state.apps.isEmpty()) model.listApps() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("app-shortcuts")) {
        Text("常用应用", style = MaterialTheme.typography.titleMedium)
        fun label(name: String) = state.apps.find { it.packageName == name }?.label ?: name
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.shortcuts.favorites.forEach { name ->
                OutlinedButton({ model.openApp(name) }, enabled = state.connected && !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(label(name)) }
            }
        }
        if (state.shortcuts.favorites.isEmpty()) Text("收藏常用应用，下次直接打开。", color = RemoteColors.Secondary)
        TextButton({ manage = !manage }, Modifier.heightIn(min = 48.dp)) { Text(if (manage) "收起收藏管理" else "管理收藏与顺序") }
        if (manage) {
            state.shortcuts.favorites.forEachIndexed { index, name ->
                Text(label(name))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ model.updateShortcuts { it.move(name, -1) } }, enabled = index > 0) { Text("上移") }
                    TextButton({ model.updateShortcuts { it.move(name, 1) } }, enabled = index < state.shortcuts.favorites.lastIndex) { Text("下移") }
                    TextButton({ model.updateShortcuts { it.toggle(name) } }) { Text("移除收藏") }
                }
            }
            OutlinedTextField(query, { query = it }, label = { Text("查找要收藏的应用") }, modifier = Modifier.fillMaxWidth())
            filterInstalledApps(state.apps, query).filterNot { it.packageName in state.shortcuts.favorites }.take(20).forEach { app ->
                TextButton({ model.updateShortcuts { it.toggle(app.packageName) } }, enabled = state.shortcuts.favorites.size < 12, modifier = Modifier.heightIn(min = 48.dp)) { Text("收藏 ${app.label ?: app.packageName}") }
            }
            if (state.appsLoading) Text("正在读取应用…")
            state.appsError?.let { Text(it, color = RemoteColors.Warning); TextButton(model::listApps, enabled = state.connected) { Text("重新加载") } }
        }
        if (state.shortcuts.recent.isNotEmpty()) {
            Text("最近通过 AI 遥控启动", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.shortcuts.recent.take(6).forEach { name ->
                    TextButton({ model.openApp(name) }, enabled = state.connected && !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(label(name)) }
                }
            }
            TextButton({ model.updateShortcuts { it.copy(recent = emptyList()) } }) { Text("清空最近记录") }
        }
    }
}
