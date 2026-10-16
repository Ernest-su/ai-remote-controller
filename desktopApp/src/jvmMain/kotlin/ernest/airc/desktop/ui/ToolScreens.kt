package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun DesktopApplications(state: DesktopState, controller: DesktopController) {
    androidx.compose.runtime.LaunchedEffect(state.connected) {
        if (state.connected && state.apps.isEmpty() && !state.appsLoading && state.appsError == null) controller.listApps()
    }
    val iconRevision by controller.appIcons.revision.collectAsState()
    val installation by controller.appInstallation.state.collectAsState()
    val management by controller.appManagement.state.collectAsState()
    val cache by controller.appCache.state.collectAsState()
    ApplicationsPanel(state.apps, state.connected, state.appsLoading, state.appsError, state.output,
        controller::listApps, controller::openApp,
        actionsBusy = state.busy || state.submitting || management.busy || installation.busy || cache.busy,
        operationMessage = when {
            management.proposal != null || cache.proposal != null || installation.proposal != null -> "有应用操作等待确认"
            management.busy || installation.busy || cache.busy -> "应用任务正在进行，可查看进度"
            management.error != null || cache.error != null || installation.error != null -> "应用任务遇到问题，请查看详情"
            management.message != null || cache.message != null || installation.message != null -> "有应用任务结果可查看"
            else -> null
        },
        onInspect = { controller.appManagement.inspect(it, state.connected) },
        onUninstall = { controller.appManagement.uninstall(it, state.connected, controller::listApps) },
        onClearCache = if("apps.clear_cache" in state.tools) { { controller.appCache.prepare(it,state.connected && !management.busy && !installation.busy) } } else null,
        icon = { name -> androidx.compose.runtime.key(iconRevision) { ApplicationIcon(name, state.connected && "apps.icon" in state.tools, controller.appIcons::load) } },
        management = { if("apps.clear_cache" in state.tools || "apps.cache_pending" in state.tools) AppCachePanel(cache,state.connected && !management.busy && !installation.busy,controller.appCache::confirm,if("apps.cache_pending" in state.tools) ({ controller.appCache.pending(state.connected && !management.busy && !installation.busy) }) else null)
            AppManagementPanel(management, state.connected && !cache.busy && !installation.busy, controller.appManagement::confirm,
            { controller.appManagement.pending(state.connected && !installation.busy && !cache.busy, controller::listApps) })
            if("apps.install_pending" in state.tools) AppInstallationPanel(installation, null, state.connected && !management.busy && !cache.busy,
                {}, controller.appInstallation::confirm, controller.appInstallation::cancel, { controller.appInstallation.pending(state.connected && !management.busy) }) })
}

@Composable
fun DesktopKeypad(state: DesktopState, controller: DesktopController) = KeypadPanel(state.connected, state.output, controller::key)

@Composable
fun DesktopTouchpad(state: DesktopState, controller: DesktopController) =
    TouchpadPanel(state.connected, state.tools, state.touchBusy, state.output, controller::gesture)

@Composable
fun DesktopTextCommands(state: DesktopState, controller: DesktopController) {
    var draft by rememberSaveable { mutableStateOf("") }
    PageContent(Modifier.testTag("text-page")) {
        Panel {
            SectionTitle("告诉设备要做什么", "先将模型配置手动更新到目标。")
            OutlinedTextField(draft, { draft = it }, label = { Text("文字指令") }, modifier = Modifier.fillMaxWidth().heightIn(min = 144.dp).testTag("text-command-input"), minLines = 4, maxLines = 8)
            TextSubmissionHint(state.connected, state.modelConfigured, state.submitting)
            ActionButton("发送指令", state.connected && state.modelConfigured && !state.submitting && draft.isNotBlank()) { if (controller.text(draft)) draft = "" }
        }
        if (state.feedback.text.isNotBlank()) Text(state.feedback.phase.label + "：" +
            if (state.feedback.phase == FeedbackPhase.ERROR) state.feedback.text else if (state.submitting) "请等待指令执行" else "可展开查看操作详情",
            modifier = Modifier.testTag("text-execution-status"), style = MaterialTheme.typography.bodyMedium,
            color = if (state.feedback.phase == FeedbackPhase.ERROR) RemoteColors.Warning else RemoteColors.Secondary)
        OperationDetails(state.output)
    }
}

@Composable
fun DesktopDeviceInfo(state: DesktopState, controller: DesktopController) = PageContent(Modifier.testTag("device-info-page")) {
    val observation by controller.systemObservation.state.collectAsState()
    if("device.system_state" in state.tools)SystemObservationPanel(observation,state.connected&&!state.busy){controller.systemObservation.refresh(state.connected&&!state.busy)}
    val system by controller.systemActions.state.collectAsState()
    if("device.system_action" in state.tools || system.requiresVerification) SystemActionPanel(system,state.connected && state.uid == 0 && !state.busy,state.name,{controller.systemActions.prepare(it,state.connected && state.uid == 0 && !state.busy)},{controller.systemActions.pending(state.connected && state.uid == 0 && !state.busy)},controller.systemActions::confirm)
    Panel {
        SectionTitle("当前设备")
        InfoRow("设备名称", state.name)
        InfoRow("连接地址", if (state.connected) "${state.host}:${state.port}" else "未连接")
        InfoRow("ADB 设备", state.serial ?: "未选择")
        InfoRow("服务版本", state.version ?: "未知")
        InfoRow("运行权限", when (state.uid) { 0 -> "root"; 2000 -> "ADB shell"; null -> "未知"; else -> "UID ${state.uid}" })
        ActionButton("刷新设备信息", state.connected, controller::deviceInfo)
        state.deviceInfo?.let { info ->
            InfoRow("制造商", info.manufacturer ?: "未知")
            InfoRow("型号", info.model ?: "未知")
            InfoRow("Android 版本", info.androidRelease ?: "未知")
            InfoRow("SDK", info.sdk?.toString() ?: "未知")
            InfoRow("ABI", info.abis?.joinToString() ?: "未知")
            InfoRow("有效屏幕尺寸", info.screen?.let { "${it.width} × ${it.height} px" } ?: "未知")
            InfoRow("可用存储", info.storage?.let { "${it.availableBytes / (1024 * 1024)} MiB" } ?: "未知")
        }
    }
    OperationDetails(state.output)
}

@Composable
fun DesktopMediaControls(state: DesktopState, controller: DesktopController) {
    val control by controller.mediaControl.state.collectAsState()
    val available = state.connected && "media.status" in state.tools
    MediaControlPanel(control, available, { controller.mediaControl.refresh(available) }, controller.mediaControl::choose, { controller.mediaControl.seek(it, available && "media.seek" in state.tools) }, { controller.mediaControl.customAction(it, available && "media.custom_action" in state.tools) })
}
