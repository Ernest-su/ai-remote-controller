package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun AppDetailsPanel(packageName: String, state: AppControlState, connected: Boolean, tools: List<String>,
    otherBusy: Boolean, onRefresh: () -> Unit, onAction: (String, String?) -> Unit, onConfirm: (Boolean) -> Unit,
    onOpen: () -> Unit, onCache: () -> Unit, onUninstall: () -> Unit, tasks: @Composable ColumnScope.() -> Unit = {}) {
    val details = state.details?.takeIf { it.app.packageName == packageName && state.packageName == packageName }
    val supported = "apps.control_info" in tools && "apps.prepare_control" in tools
    val enabled = connected && supported && !state.busy && !otherBusy && details != null
    state.proposal?.takeIf { it.app.packageName == packageName }?.let { p ->
        AlertDialog(onDismissRequest = { onConfirm(false) }, title = { Text("确认${appControlLabel(p.action)}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(p.app.packageName)
                Text("版本 ${p.app.versionName ?: p.app.versionCode} · Android 用户 ${p.app.userId}")
                p.permission?.let { Text(it) }
                Text(appControlImpact(p.action))
                Text("确认后提交，提交后不能取消。")
            } },
            confirmButton = { TextButton({ onConfirm(true) }, enabled = connected && !otherBusy, modifier = Modifier.heightIn(min = 48.dp).testTag("app-control-confirm")) {
                Text("确认${appControlLabel(p.action)}", color = if (p.action in listOf("clear_data", "disable", "revoke")) RemoteColors.Warning else RemoteColors.Action)
            } },
            dismissButton = { TextButton({ onConfirm(false) }, Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    }
    PageContent(Modifier.testTag("app-details-page")) {
        Panel {
            Text("应用信息", style = MaterialTheme.typography.titleMedium)
            InfoRow("包名", packageName)
            details?.let { d ->
                InfoRow("版本", d.app.versionName ?: d.app.versionCode.toString())
                InfoRow("Android 用户", d.app.userId.toString())
                InfoRow("类型", if (d.app.system) "系统应用" else "用户应用")
                InfoRow("状态", if (d.state.enabled) "已启用" else "已停用")
                InfoRow("停止标记", if (d.state.stopped) "已停止" else "未标记停止（不表示正在运行）")
            }
            if (!connected) Text("设备未连接，连接后可刷新并管理应用。", color = RemoteColors.Secondary)
            else if (!supported) Text("当前目标未提供应用详情管理接口，请更新配套服务。", color = RemoteColors.Secondary)
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (state.committing) "正在提交应用操作，提交后不能取消" else if (state.proposal != null) "等待确认" else "正在读取应用状态…")
            }
            state.error?.let { Text(it, color = RemoteColors.Warning) }
            state.message?.let { Text(it, color = RemoteColors.Secondary) }
            AppDetailAction("刷新应用详情", connected && supported && !state.busy && !otherBusy, action = onRefresh)
        }
        details?.let { d ->
            val userApp = !d.app.system && d.app.appId >= 10000
            Panel {
                Text("运行管理", style = MaterialTheme.typography.titleMedium)
                ActionButton("启动应用", enabled && d.state.enabled, onOpen)
                AppDetailAction("强行停止", enabled && d.state.capabilities.forceStop) { onAction("force_stop", null) }
                AppDetailAction(if (d.state.enabled) "停用应用" else "启用应用", enabled && d.state.capabilities.setEnabled) { onAction(if (d.state.enabled) "disable" else "enable", null) }
                if (!userApp) Text("系统及特权应用仅供查看。", color = RemoteColors.Secondary)
                else if (!d.state.capabilities.forceStop || !d.state.capabilities.setEnabled) Text("部分运行操作不可用，服务缺少系统授权。", color = RemoteColors.Secondary)
            }
            Panel {
                Text("存储管理", style = MaterialTheme.typography.titleMedium)
                Text("清理缓存可能需要重新下载内容；清除全部数据还会删除登录状态、设置和应用私有文件。", color = RemoteColors.Secondary)
                AppDetailAction("清理缓存", enabled && userApp && "apps.clear_cache" in tools, action = onCache)
                AppDetailAction("清除全部数据", enabled && d.state.capabilities.clearData, destructive = true) { onAction("clear_data", null) }
                AppDetailAction("卸载应用", enabled && userApp && "apps.uninstall" in tools, destructive = true, action = onUninstall)
                if (userApp && !d.state.capabilities.clearData) Text("服务缺少清除应用数据的系统授权。", color = RemoteColors.Secondary)
            }
            Panel {
                Text("权限管理", style = MaterialTheme.typography.titleMedium)
                Text("显示目标实际授权状态。特殊访问和安装时权限仅供查看；特殊访问的开关状态需在目标系统设置中核对。", color = RemoteColors.Secondary)
                if (d.state.permissions.isEmpty()) Text("此应用未声明权限。", color = RemoteColors.Secondary)
                d.state.permissions.forEach { permission ->
                    val rowModifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).let {
                        if (permission.runtime && permission.mutable) it.toggleable(value = permission.granted, enabled = enabled, role = Role.Switch,
                            onValueChange = { granted -> onAction(if (granted) "grant" else "revoke", permission.name) }).testTag("app-permission-${permission.name}") else it
                    }
                    Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(permission.label, style = MaterialTheme.typography.bodyLarge)
                            Text(permission.name, style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                            Text(if (permission.granted) "权限已授予" else "权限未授予", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                            if (!permission.mutable) Text(permission.reason, style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                        }
                        if (permission.runtime && permission.mutable) Switch(checked = permission.granted, enabled = enabled,
                            onCheckedChange = null,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
                    }
                    HorizontalDivider(color = RemoteColors.Divider, thickness = 0.5.dp)
                }
            }
        }
        tasks()
    }
}

@Composable
private fun AppDetailAction(label: String, enabled: Boolean, destructive: Boolean = false, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (destructive) RemoteColors.Warning else RemoteColors.Text)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
