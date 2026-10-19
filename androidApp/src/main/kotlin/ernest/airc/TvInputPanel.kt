package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun TvInputPanel(state: RemoteUiState, model: RemoteViewModel, requestEditorFocus: Boolean = false) {
    val input by model.tvInput.state.collectAsState()
    val view = LocalView.current
    // This editor lives in its own Dialog window; the Activity's adjustResize does not apply.
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val previous = window?.attributes?.softInputMode
        onDispose { if (previous != null) window?.setSoftInputMode(previous) }
    }
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(state.selected, state.connected) {
        model.tvInput.selectTarget(state.selected)
        if (state.connected) model.tvInput.refresh(true)
    }
    LaunchedEffect(input.message) {
        if (input.message != null && input.draft.isEmpty()) { focus.clearFocus(); keyboard?.hide() }
    }
    TvInputContent(input, state.history.find { it.id == state.selected }?.visibleName ?: "尚未选择电视", state.connected,
        model.tvInput::edit, model.tvInput::mode, { model.tvInput.send(state.connected) },
        { clipboard.getText()?.text?.let(model.tvInput::edit) }, { model.key("delete") }, { model.key("enter") },
        { model.tvInput.enable(state.connected) }, { model.tvInput.refresh(state.connected) },
        { model.tvInput.restore(state.connected) }, model.tvInput::cancel,
        direction = model::key, requestEditorFocus = requestEditorFocus)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvInputContent(input: TvInputState, targetName: String, connected: Boolean,
    edit: (String) -> Unit, mode: (TvInputMode) -> Unit, send: () -> Unit, pastePhone: () -> Unit,
    delete: () -> Unit, enter: () -> Unit, enable: () -> Unit, refresh: () -> Unit, restore: () -> Unit, cancel: () -> Unit, direction: (String) -> Unit = {}, requestEditorFocus: Boolean = false) {
    val editorFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var editorRequested by remember { mutableStateOf(false) }
    LaunchedEffect(requestEditorFocus, input.busy) {
        if (requestEditorFocus && !input.busy && !editorRequested) {
            // Let the initial status refresh disable/re-enable the editor before requesting focus.
            withFrameNanos { }
            editorFocus.requestFocus(); keyboard?.show()
            editorRequested = true
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvInputIcon(R.drawable.ic_device_tv)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(targetName, style = MaterialTheme.typography.titleMedium)
            Text(if (connected) "先在电视上选中输入框，再发送文字" else "连接电视后即可发送，草稿会保留",
                style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        }
    }
    Panel {
        SectionTitle("输入方式")
        Column(Modifier.selectableGroup()) {
            TvInputMode.entries.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = RemoteColors.Divider)
                Row(Modifier.fillMaxWidth().heightIn(min = RemoteDimensions.SettingRowHeight)
                    .testTag("tv-input-mode-${item.name.lowercase()}")
                    .selectable(input.mode == item, enabled = !input.busy, role = Role.RadioButton, onClick = { mode(item) })
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvInputIcon(if (item == TvInputMode.IME) R.drawable.ic_keypad else R.drawable.ic_clipboard)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title, style = MaterialTheme.typography.bodyLarge)
                        Text(if (item == TvInputMode.IME) "直接提交中文，不改电视剪贴板" else "无需安装输入法，应用需允许粘贴",
                            style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                    }
                    RadioButton(input.mode == item, onClick = null, enabled = !input.busy)
                }
            }
        }
        if (input.mode == TvInputMode.IME) {
            HorizontalDivider(thickness = 0.5.dp, color = RemoteColors.Divider)
            val status = input.ime
            Text(when {
                !connected -> "连接后可安装并启用 AIRC 电视输入法。"
                status == null -> "检查输入法状态，或安装并启用后使用。"
                !status.installed -> "首次使用需在电视安装 AIRC 输入法。"
                !status.aligned -> "电视输入法需要更新至当前配套版本。"
                !status.selected -> "AIRC 输入法已安装，启用后即可提交。"
                !status.ready -> "AIRC 输入法已启用，请在电视重新选中输入框。"
                else -> "输入法已就绪，可以发送文字。"
            }, style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary,
                modifier = Modifier.testTag("tv-input-ime-status"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enable, enabled = connected && !input.busy, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("tv-input-enable")) {
                    TvInputIcon(R.drawable.ic_service); Spacer(Modifier.width(8.dp))
                    Text(if (status?.selected == true) "重新启用" else if (status?.installed == true && !status.aligned) "更新并启用" else if (status?.aligned == true) "启用输入法" else "安装并启用")
                }
                TvInputTextAction("检查状态", R.drawable.ic_refresh, connected && !input.busy, refresh)
            }
            Text("启用会切换电视当前输入法，可随时恢复。目标输入框需支持系统输入法。",
                style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
        }
        if (input.ime?.previous != null) {
            TvInputTextAction("恢复电视原输入法", R.drawable.ic_restore, connected && !input.busy, restore)
        }
    }
    Panel {
        OutlinedTextField(input.draft, edit, enabled = !input.busy, label = { Text("输入到电视") },
            placeholder = { Text("输入片名、网址或其他文字") },
            modifier = Modifier.fillMaxWidth().focusRequester(editorFocus).testTag("tv-input-draft"), minLines = 3, maxLines = 5,
            isError = input.draft.toByteArray().size > 65536,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false))
        if (input.draft.toByteArray().size > 65536) Text("文字过长，请缩短后发送（最多 65536 UTF-8 字节）。",
            style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Warning)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvInputTextAction("粘贴手机剪贴板", R.drawable.ic_clipboard, !input.busy, pastePhone)
            TvInputTextAction("清空草稿", R.drawable.ic_close, !input.busy && input.draft.isNotEmpty(), { edit("") })
        }
        Button(send, enabled = connected && !input.busy && input.draft.isNotBlank() && input.draft.toByteArray().size <= 65536 &&
            (input.mode == TvInputMode.PASTE || input.ime?.let { it.aligned && it.selected } == true),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tv-input-send"), shape = MaterialTheme.shapes.small) {
            TvInputIcon(R.drawable.ic_send); Spacer(Modifier.width(8.dp)); Text("发送到电视")
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tv-input-feedback")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (input.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(input.progress ?: "正在处理…", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                    TextButton(cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消操作") }
                } else {
                    val feedback = input.error ?: input.message ?: if (input.mode == TvInputMode.PASTE)
                        "发送会替换电视剪贴板，不会自动同步两端内容。" else "仅提交文字；搜索或确认请使用下方回车。"
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        if (input.error != null) TvInputIcon(R.drawable.ic_error, warning = true)
                        Text(feedback, style = MaterialTheme.typography.bodyMedium,
                            color = if (input.error != null) RemoteColors.Warning else RemoteColors.Secondary)
                    }
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvInputTextAction("删除一字", R.drawable.ic_delete, connected && !input.busy, delete)
        TvInputTextAction("回车", R.drawable.ic_enter, connected && !input.busy, enter)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple("向左", "left", R.drawable.ic_left), Triple("向上", "up", R.drawable.ic_up),
            Triple("向下", "down", R.drawable.ic_down), Triple("向右", "right", R.drawable.ic_right)).forEach { (label, key, icon) ->
            TvInputTextAction(label, icon, connected && !input.busy, { direction(key) })
        }
    }
    Text("焦点联动需先安装并启用 AIRC 输入法；检测到电视输入框后，遥控与预览页会显示输入入口。",
        style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
    Text("无需模型，需要可用的 ADB 连接。草稿只在本次 App 进程中保留，不保存输入历史。",
        style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
}

@Composable
private fun TvInputIcon(resource: Int, warning: Boolean = false) {
    Icon(painterResource(resource), contentDescription = null, modifier = Modifier.size(RemoteDimensions.DeviceIcon),
        tint = if (warning) RemoteColors.Warning else LocalContentColor.current)
}

@Composable
private fun TvInputTextAction(label: String, icon: Int, enabled: Boolean, action: () -> Unit) {
    TextButton(action, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        TvInputIcon(icon); Spacer(Modifier.width(8.dp)); Text(label)
    }
}
