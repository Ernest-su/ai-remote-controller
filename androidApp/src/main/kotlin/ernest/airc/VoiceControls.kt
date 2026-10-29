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
internal fun VoiceControls(controller: AndroidVoiceController, connected: Boolean) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = LocalWindowInfo.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) controller.permissionDenied()
    }
    fun begin() {
        if (!connected || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !window.isWindowFocused) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) controller.start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) controller.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.stop() }
    }
    LaunchedEffect(window.isWindowFocused, connected) { if (!window.isWindowFocused || !connected) controller.stop() }
    val active = state.preparing || state.recording
    val color = if (!connected) RemoteColors.Disabled else if (active) RemoteColors.Action else RemoteColors.Text
    Column(Modifier.fillMaxWidth().testTag("voice-controls")) {
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
            .onFocusChanged { if (!it.isFocused) controller.stop() }
            .onKeyEvent { event ->
                if (connected && event.key in listOf(Key.Enter, Key.Spacebar, Key.DirectionCenter)) {
                    if (event.type == KeyEventType.KeyDown) begin() else if (event.type == KeyEventType.KeyUp) controller.stop()
                    true
                } else false
            }.focusable(connected)
            .pointerInput(connected, controller) {
                if (connected) detectTapGestures(onPress = {
                    begin()
                    try { tryAwaitRelease() } finally { controller.stop() }
                })
            }.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_microphone), null, Modifier.size(24.dp), tint = color)
            Text(if (state.preparing) "正在连接…" else if (state.recording) "松开结束" else "按住说话", color = color,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (active) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = RemoteColors.Action)
        }
        if (state.message.startsWith("语音失败") || state.message.startsWith("需要") || state.message.startsWith("请输入") || state.message.startsWith("附加键值")) {
            Text(state.message, color = RemoteColors.Warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp).testTag("voice-error"))
        }
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
