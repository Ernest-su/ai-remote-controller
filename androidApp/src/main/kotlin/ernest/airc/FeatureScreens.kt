package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
internal fun TextCommands(state: RemoteUiState, model: RemoteViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var observe by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    PageContent(Modifier.testTag("text-page")) {
        Panel {
            SectionTitle("告诉设备要做什么", "自然语言任务需要先在功能菜单中配置模型。")
            OutlinedTextField(text, { text = it }, label = { Text("文字指令") }, modifier = Modifier.fillMaxWidth().heightIn(min = 144.dp).testTag("text-command-input"), minLines = 4, maxLines = 8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!observe, { observe = false }, enabled = !state.submitting, label = { Text("文字任务") }, modifier = Modifier.heightIn(min = 48.dp))
                FilterChip(observe, { observe = true }, enabled = !state.submitting, label = { Text("看图操作") }, modifier = Modifier.heightIn(min = 48.dp))
            }
            if (observe) Text("此步骤需要读取电视画面并发送给已配置的模型服务。需要支持图片与工具调用，可能增加费用；最多8轮操作，执行后重新观察。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
            if (state.submitting) OutlinedButton(model::stopText, modifier = Modifier.heightIn(min = 48.dp)) { Text("停止任务") }
            TextSubmissionHint(state.connected, state.modelConfigured, state.submitting)
            ActionButton("发送指令", state.connected && state.modelConfigured && !state.submitting && text.isNotBlank()) { if (model.text(text, observe)) { text = ""; focus.clearFocus(); keyboard?.hide() } }
        }
        if (state.feedback.text.isNotBlank()) Text(state.feedback.phase.label + "：" +
            if (state.feedback.phase == FeedbackPhase.ERROR) state.feedback.text else if (state.submitting) "请等待指令执行" else "可展开查看操作详情",
            modifier = Modifier.testTag("text-execution-status"), style = MaterialTheme.typography.bodyMedium,
            color = if (state.feedback.phase == FeedbackPhase.ERROR) RemoteColors.Warning else RemoteColors.Secondary)
        OperationDetails(state.output)
    }
}

@Composable
internal fun ServiceSettings(state: RemoteUiState, model: RemoteViewModel) {
    val uri = LocalUriHandler.current
    val target = state.history.firstOrNull { it.id == state.selected }
    val manageable = !state.busy && target?.adbPort != null
    var confirmStop by remember { mutableStateOf(false) }
    PageContent(Modifier.testTag("service-page")) {
        Panel(Modifier.testTag("manage-service-card")) {
            SectionTitle("管理服务", "安装或更新设备端服务，保持遥控连接可用。")
            InfoRow("服务端版本", state.version ?: "未连接")
            TextButton({ uri.openUri(RuntimeReleaseRepository.GITHUB_REPOSITORY) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("GitHub 在线版本", style = MaterialTheme.typography.bodyMedium)
            }
            if (target?.adbPort == null) Text("请先在设备页通过 ADB 连接目标设备，服务管理需要已授权的 ADB 连接。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
            ActionButton("检查 / 安装在线版本", manageable, model::updateRuntime)
            OutlinedButton(model::serviceStatus, enabled = manageable, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查询状态") }
            OutlinedButton(model::restartService, enabled = manageable, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重启目标服务") }
            TextButton({ confirmStop = true }, enabled = manageable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = RemoteColors.Warning)) { Text("停止目标服务") }
            if (state.busy) TextButton(model::cancelConnection, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消操作") }
            if (state.busy) Text(state.status, style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        }
        OperationDetails(state.output)
    }
    if (confirmStop) StopServiceConfirmation(
        enabled = manageable,
        onDismiss = { confirmStop = false },
        onConfirm = { confirmStop = false; model.stopService() },
    )
}

internal fun runtimePermissionLabel(state: RemoteUiState): String = when (if (state.connected) state.runtimeUid else null) {
    0 -> "root"
    2000 -> "ADB shell"
    null -> "未知 / 未连接"
    else -> "UID ${state.runtimeUid}"
}

@Composable
internal fun PermissionSettings(state: RemoteUiState, model: RemoteViewModel) {
    PageContent(Modifier.testTag("permissions-page")) {
        Panel {
            SectionTitle("运行权限")
            ToggleSettingRow("优先使用 root 权限", state.preferRoot, !state.busy,
                description = "目标支持时使用 root，否则使用普通调试权限。切换后会重启设备端服务。",
                modifier = Modifier.testTag("prefer-root"), onCheckedChange = model::setPreferRoot)
            HorizontalDivider(color = RemoteColors.Divider)
            InfoRow("当前运行权限", runtimePermissionLabel(state))
            Text("设置保存在控制手机；root 管理器可能需要在目标设备上批准授权。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        }
        OperationDetails(state.output)
    }
}

@Composable
internal fun HelpScreen() {
    PageContent(Modifier.testTag("help-page")) {
        Panel {
            SectionTitle("开始遥控")
            Text("1. 点击右上角的连接状态，连接目标设备。")
            Text("2. 在遥控器使用方向键、确认、返回、主页和音量。")
            Text("3. 点击左上角菜单，在功能网格中打开其他功能。")
        }
        Panel {
            SectionTitle("发送文字指令")
            Text("先在功能网格的「模型配置」中保存 App 配置，再点击「更新到目标设备」。在遥控器底部输入指令，点击发送图标；也可进入「文字指令」页面编辑。执行状态在遥控器中央显示。")
        }
        Panel {
            SectionTitle("连接与服务")
            Text("ADB 用于部署和管理设备端程序；日常遥控使用局域网服务。服务管理与运行权限切换需要目标 ADB 可访问。")
            Text("仅在可信局域网使用。设备端目前没有登录或交互确认，root 模式下工具会以 root 执行。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        }
    }
}
