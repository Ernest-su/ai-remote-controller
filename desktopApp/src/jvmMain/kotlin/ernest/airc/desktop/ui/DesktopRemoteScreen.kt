package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun DesktopRemoteScreen(state: DesktopState, model: DesktopController, showResults: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        if (model.text(draft)) {
            draft = ""
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    var feedbackVisible by remember { mutableStateOf(state.feedback.remainingVisibleMillis() > 0L) }
    LaunchedEffect(state.feedback.revision) {
        val remaining = state.feedback.remainingVisibleMillis()
        feedbackVisible = remaining > 0L
        if (feedbackVisible) {
            delay(remaining)
            feedbackVisible = false
        }
    }
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(1f)
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    Box(Modifier.fillMaxSize().testTag("remote-page").padding(
        start = RemoteDimensions.OuterPadding, end = RemoteDimensions.OuterPadding,
        top = RemoteDimensions.OuterPadding, bottom = if (imeVisible) 0.dp else RemoteDimensions.OuterPadding,
    ), contentAlignment = Alignment.TopCenter) {
        Surface(shape = MaterialTheme.shapes.small, color = RemoteColors.Fill,
            modifier = Modifier.widthIn(max = RemoteDimensions.Width).fillMaxWidth().fillMaxHeight()) {
            Column(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val panelHeight = maxOf(maxHeight, RemoteDimensions.MinimumControlsHeight * fontScale)
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Column(
                            Modifier.height(panelHeight).fillMaxWidth().padding(
                                start = RemoteDimensions.InnerPadding, end = RemoteDimensions.InnerPadding,
                                top = RemoteDimensions.VerticalPadding, bottom = 8.dp,
                            ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                IconButton({ model.key("power") }, enabled = state.connected,
                                    modifier = Modifier.size(48.dp).background(RemoteColors.Surface, CircleShape)) {
                                    Icon(painterResource("drawable/ic_power.xml"), "电源", Modifier.size(24.dp),
                                        tint = if (state.connected) RemoteColors.Warning else RemoteColors.Disabled)
                                }
                                Row {
                                    IconButton({ model.key("volume_down") }, enabled = state.connected) { Icon(painterResource("drawable/ic_volume_down.xml"), "音量 −") }
                                    IconButton({ model.key("mute") }, enabled = state.connected) { Icon(painterResource("drawable/ic_mute.xml"), "静音") }
                                    IconButton({ model.key("volume_up") }, enabled = state.connected) { Icon(painterResource("drawable/ic_volume_up.xml"), "音量 +") }
                                }
                            }
                            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    Surface(shape = MaterialTheme.shapes.small, color = RemoteColors.Surface,
                                        modifier = Modifier.fillMaxWidth().testTag("remote-status")) {
                                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (feedbackVisible) {
                                                Text(state.feedback.phase.label, style = MaterialTheme.typography.bodySmall,
                                                    color = if (state.feedback.phase == FeedbackPhase.ERROR) RemoteColors.Warning else RemoteColors.Secondary)
                                                Text(state.feedback.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("remote-feedback"))
                                                TextButton(showResults, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看执行结果", style = MaterialTheme.typography.bodySmall) }
                                            } else {
                                                Text("播放状态", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                                                Text(state.media, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("remote-playback"))
                                            }
                                        }
                                    }
                                }
                                if (!feedbackVisible) {
                                    Row(Modifier.fillMaxWidth().testTag("remote-media-controls"), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        RemoteKey("drawable/ic_previous.xml", "上一曲", state.connected, size = 52.dp) { model.key("media_previous") }
                                        RemoteKey("drawable/ic_play_pause.xml", "播放 / 暂停", state.connected, size = 52.dp) { model.key("play_pause") }
                                        RemoteKey("drawable/ic_next.xml", "下一曲", state.connected, size = 52.dp) { model.key("media_next") }
                                    }
                                }
                            }
                            Box(Modifier.size(RemoteDimensions.Dpad).testTag("remote-dpad").background(RemoteColors.Surface, CircleShape).border(0.5.dp, RemoteColors.Divider, CircleShape)) {
                                @Composable fun Direction(icon: String, label: String, key: String, alignment: Alignment, padding: PaddingValues) {
                                    IconButton({ model.key(key) }, enabled = state.connected, modifier = Modifier.align(alignment).padding(padding).size(60.dp)) {
                                        Icon(painterResource(icon), label, Modifier.size(28.dp))
                                    }
                                }
                                Direction("drawable/ic_up.xml", "向上", "up", Alignment.TopCenter, PaddingValues(top = 8.dp))
                                Direction("drawable/ic_down.xml", "向下", "down", Alignment.BottomCenter, PaddingValues(bottom = 8.dp))
                                Direction("drawable/ic_left.xml", "向左", "left", Alignment.CenterStart, PaddingValues(start = 8.dp))
                                Direction("drawable/ic_right.xml", "向右", "right", Alignment.CenterEnd, PaddingValues(end = 8.dp))
                                Button({ model.key("ok") }, enabled = state.connected, shape = CircleShape,
                                    modifier = Modifier.size(RemoteDimensions.Confirm).align(Alignment.Center), contentPadding = PaddingValues(0.dp)) {
                                    Text("OK", fontSize = 20.sp)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                RemoteKey("drawable/ic_back.xml", "返回", state.connected, size = 58.dp) { model.key("back") }
                                RemoteKey("drawable/ic_home.xml", "主页", state.connected, size = 58.dp) { model.key("home") }
                                RemoteKey("drawable/ic_device_menu.xml", "设备菜单", state.connected, size = 58.dp) { model.key("menu") }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = RemoteDimensions.InnerPadding, end = RemoteDimensions.InnerPadding, bottom = if (imeVisible) 8.dp else RemoteDimensions.VerticalPadding).testTag("remote-text-controls"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        modifier = Modifier.weight(1f).testTag("remote-command-input"),
                        placeholder = { Text("输入文字指令", style = MaterialTheme.typography.bodyMedium) },
                        minLines = 1, maxLines = 3,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                    )
                    FilledIconButton(onClick = ::submit,
                        enabled = state.connected && state.modelConfigured && !state.submitting && draft.isNotBlank(),
                        modifier = Modifier.size(48.dp).testTag("remote-send-command")) {
                        Icon(painterResource("drawable/ic_send.xml"), "发送文字指令", Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun RemoteKey(icon: String, label: String, enabled: Boolean, tint: Color = RemoteColors.Text, size: androidx.compose.ui.unit.Dp = 48.dp, action: () -> Unit) {
    Column(
        Modifier.widthIn(min = 64.dp).clickable(enabled = enabled, role = Role.Button, onClick = action)
            .semantics(mergeDescendants = true) {}.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(size).background(RemoteColors.Surface, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(24.dp), tint = if (enabled) tint else RemoteColors.Disabled)
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (enabled) RemoteColors.Text else RemoteColors.Disabled)
    }
}
