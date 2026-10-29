package ernest.airc

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun VoiceControls(controller: AndroidVoiceController, connected: Boolean, modifier: Modifier = Modifier, showError: Boolean = true) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = LocalWindowInfo.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) controller.permissionDenied()
    }
    var keyboardPress by remember { mutableStateOf<Long?>(null) }
    fun begin(): Long? {
        if (!connected || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !window.isWindowFocused) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return controller.start()
        permission.launch(Manifest.permission.RECORD_AUDIO)
        return null
    }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) controller.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.stop() }
    }
    LaunchedEffect(window.isWindowFocused, connected) { if (!window.isWindowFocused || !connected) controller.stop() }
    val active = state.preparing || state.recording
    val color = if (!connected) RemoteColors.Disabled else if (active) RemoteColors.Action else RemoteColors.Text
    Column(modifier.fillMaxWidth().testTag("voice-controls")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (active) RemoteColors.ActionContainer else RemoteColors.Surface, MaterialTheme.shapes.small)
            .semantics {
                role = Role.Button
                contentDescription = "按住说话"
                stateDescription = if (state.preparing) "正在连接" else if (state.recording) "正在录音，松开结束" else "未录音"
                if (!connected) disabled()
                onClick(label = if (active) "结束说话" else "开始说话") {
                    if (connected) { if (active) controller.stop() else begin() }; connected
                }
            }
            .onFocusChanged { if (!it.isFocused) { keyboardPress?.let(controller::stop); keyboardPress = null } }
            .onKeyEvent { event ->
                if (connected && event.key in listOf(Key.Enter, Key.Spacebar, Key.DirectionCenter)) {
                    if (event.type == KeyEventType.KeyDown && keyboardPress == null) keyboardPress = begin()
                    else if (event.type == KeyEventType.KeyUp) { keyboardPress?.let(controller::stop); keyboardPress = null }
                    true
                } else false
            }.focusable(connected)
            .pointerInput(connected, controller) {
                if (connected) detectTapGestures(onPress = {
                    val token = begin()
                    try { tryAwaitRelease() } finally { if (token != null) controller.stop(token) }
                })
            }.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_microphone), null, Modifier.size(24.dp), tint = color)
            Text(if (state.preparing) "正在连接…" else if (state.recording) "松开结束" else "按住说话", color = color,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (active) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = RemoteColors.Action)
        }
        if (showError) VoiceError(state.message)
    }
}

@Composable
private fun VoiceError(message: String) {
    if (message.startsWith("语音失败") || message.startsWith("需要") || message.startsWith("请输入") || message.startsWith("附加键值")) {
        Text(message, color = RemoteColors.Warning, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp).testTag("voice-error").semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** One input area; switching modes never discards the text draft or resumes recording. */
@Composable
internal fun RemoteCommandInput(controller: AndroidVoiceController, connected: Boolean, modelConfigured: Boolean,
    submitting: Boolean, draft: String, edit: (String) -> Unit, submit: () -> Unit, modifier: Modifier = Modifier) {
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    val state by controller.state.collectAsState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier.testTag("remote-text-controls")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton({
                controller.stop()
                focus.clearFocus()
                keyboard?.hide()
                voiceMode = !voiceMode
            }, Modifier.size(48.dp).testTag("remote-input-mode")) {
                Icon(painterResource(if (voiceMode) R.drawable.ic_keyboard else R.drawable.ic_microphone),
                    if (voiceMode) "切换为文字输入" else "切换为语音输入", Modifier.size(24.dp))
            }
            if (voiceMode) {
                VoiceControls(controller, connected, Modifier.weight(1f), showError = false)
            } else {
                OutlinedTextField(draft, edit, Modifier.weight(1f).testTag("remote-command-input"),
                    placeholder = { Text("输入指令", style = MaterialTheme.typography.bodyMedium) },
                    minLines = 1, maxLines = 3, textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }))
                FilledIconButton(submit, enabled = connected && modelConfigured && !submitting && draft.isNotBlank(),
                    modifier = Modifier.size(48.dp).testTag("remote-send-command"),
                    colors = if (LocalMirrorOverlay.current) IconButtonDefaults.filledIconButtonColors(
                        containerColor = RemoteColors.Surface, contentColor = RemoteColors.Text,
                        disabledContainerColor = RemoteColors.Surface, disabledContentColor = RemoteColors.Disabled,
                    ) else IconButtonDefaults.filledIconButtonColors()) {
                    Icon(painterResource(R.drawable.ic_send), "发送文字指令", Modifier.size(24.dp))
                }
            }
        }
        if (voiceMode) VoiceError(state.message)
        else if (!modelConfigured) Text("文字指令需先配置模型", color = RemoteColors.Secondary,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("remote-model-required"))
    }
}

@Composable
internal fun VoiceSettingsPanel(controller: AndroidVoiceController) {
    val state by controller.state.collectAsState()
    Panel {
        SectionTitle("语音遥控")
        Text("按住遥控器的语音按钮，将手机麦克风发送给电视上的应用；不需要配置模型。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        OutlinedTextField(state.settings.keyCode, { controller.settings(state.settings.copy(keyCode = it)) },
            label = { Text("附加按键值（可留空）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("按下时发送 DOWN，松开时发送 UP；康佳为 1017。") }, modifier = Modifier.fillMaxWidth().testTag("voice-key-code"))
        OutlinedTextField(state.settings.packageName, { controller.settings(state.settings.copy(packageName = it.trim())) },
            label = { Text("接收语音的应用包名") }, singleLine = true,
            supportingText = { Text("康佳默认 com.konka.bestlauncher；只替换此应用 UID 的 MIC 输入。") }, modifier = Modifier.fillMaxWidth().testTag("voice-package"))
        Text("被控端需 Android 14+、音频路由权限与 remote submix 支持。单次最长60秒；离页、后台或断线自动结束。设置保存在此控制端，录音不保存。",
            style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
    }
}
