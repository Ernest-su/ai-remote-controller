package ernest.airc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MediaControlPanel(state: MediaControlState, available: Boolean, onRefresh: () -> Unit, onChoose: (MediaSelection) -> Unit,
    onSeek: (Long) -> Unit, onCustomAction: (String) -> Unit = {}, onCommand: (String) -> Unit = {}, volumeControls: @Composable () -> Unit = {}) {
    var target by rememberSaveable { mutableStateOf("") }
    var precise by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var dragging by remember(state.selected) { mutableStateOf<Float?>(null) }
    var pending by remember(state.selected) { mutableStateOf<Float?>(null) }
    val session = state.sessions.singleOrNull { it.active && it.selection() == state.selected }
    val duration = session?.durationMs?.takeIf { it > 0 }
    val position = session?.positionMs?.takeIf { it >= 0 }
    val canSeek = available && !state.busy && state.seekAvailable && session?.seekSupported == true
    LaunchedEffect(state.busy, position, pending) { if (!state.busy) pending = null }
    fun seek(value: Long) { pending = duration?.let { value.toFloat() / it }; onSeek(value) }
    PageContent(Modifier.testTag("media-control-page")) {
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("播放来源", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    Text(session?.playerName() ?: "媒体播放器", style = MaterialTheme.typography.titleMedium)
                }
                if (state.sessions.size > 1) TextButton({ choosing = true }, enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("media-choose-player")) { Text("切换播放器") }
                IconButton(onRefresh, enabled = available && !state.busy && !state.manualRefreshing,
                    modifier = Modifier.size(48.dp).testTag("media-refresh")) {
                    if (state.manualRefreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = RemoteColors.Action)
                    else Icon(ManagementIcons.Refresh, "刷新播放状态", Modifier.size(24.dp))
                }
            }
            when {
                session != null -> {
                    Text(session.description?.takeIf { it.isNotBlank() } ?: "未提供内容标题",
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("media-title"))
                    listOfNotNull(session.artist?.takeIf(String::isNotBlank), session.album?.takeIf(String::isNotBlank))
                        .distinct().takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                        }
                    Text(if (available) session.playbackLabel() else "设备已断开", style = MaterialTheme.typography.bodySmall,
                        color = RemoteColors.Secondary, modifier = Modifier.testTag("media-playback-state"))
                    val requestedFraction = state.pendingSeekMs?.let { requested -> duration?.let { requested.toFloat() / it } }
                    val fraction = dragging ?: pending ?: requestedFraction ?: if (duration != null && position != null) (position.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
                    val progressColors = SliderDefaults.colors(thumbColor = RemoteColors.Action, activeTrackColor = RemoteColors.Action, inactiveTrackColor = RemoteColors.Outline)
                    Slider(value = fraction, onValueChange = { dragging = it }, onValueChangeFinished = {
                        if (duration != null) dragging?.let { seek((duration * it.toDouble()).toLong().coerceIn(0, duration)) }
                        dragging = null
                    }, enabled = canSeek && duration != null && position != null,
                        colors = progressColors,
                        thumb = { Box(Modifier.size(RemoteDimensions.PlaybackThumbSize).background(if (canSeek) RemoteColors.Action else RemoteColors.Disabled, CircleShape).testTag("media-seek-thumb")) },
                        track = { progress -> SliderDefaults.Track(progress, Modifier.height(RemoteDimensions.PlaybackTrackHeight), enabled = canSeek && duration != null && position != null,
                            colors = progressColors, thumbTrackGapSize = 0.dp, drawStopIndicator = null) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-seek-slider").semantics { contentDescription = "播放进度" })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if ((dragging ?: pending ?: requestedFraction) != null && duration != null) "目标 ${formatPlaybackTime((duration * fraction.toDouble()).toLong())}" else position?.let(::formatPlaybackTime) ?: "--:--",
                            style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                        Text(duration?.let(::formatPlaybackTime) ?: "--:--", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    }
                    volumeControls()
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        @Composable fun control(command: String, label: String, icon: ImageVector, primary: Boolean = false) {
                            val enabled = available && !state.busy && state.transportAvailable && session.supportsCommand(command)
                            if (primary) FilledIconButton({ onCommand(command) }, enabled = enabled, modifier = Modifier.size(RemoteDimensions.PlaybackMainButton).testTag("media-command-$command"),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = RemoteColors.Action, contentColor = RemoteColors.OnAction,
                                    disabledContainerColor = RemoteColors.Fill, disabledContentColor = RemoteColors.Disabled)) {
                                Icon(icon, label, Modifier.size(32.dp))
                            } else IconButton({ onCommand(command) }, enabled = enabled, modifier = Modifier.size(RemoteDimensions.PlaybackButton).testTag("media-command-$command")) {
                                Icon(icon, label, Modifier.size(28.dp))
                            }
                        }
                        control("previous", "上一首", ManagementIcons.PreviousTrack)
                        val playing = session.state in listOf(3, 6, 8)
                        control(if (playing) "pause" else "play", if (playing) "暂停" else "播放", if (playing) ManagementIcons.Pause else ManagementIcons.Play, true)
                        control("next", "下一首", ManagementIcons.NextTrack)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(-10L, 10L).forEach { seconds ->
                            OutlinedButton({ position?.let { seek(relativePlaybackPosition(it, seconds, duration)) } }, enabled = canSeek && position != null,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                                Text(if (seconds < 0) "后退 10 秒" else "前进 10 秒")
                            }
                        }
                    }
                    if (!state.transportAvailable || listOf("play", "pause", "previous", "next").none(session::supportsCommand))
                        Text(if (!state.transportAvailable) "当前设备的播放按键暂不可用" else "此播放器未提供播放按键", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    if (session.seekSupported != true || !state.seekAvailable)
                        Text(if (!state.seekAvailable) "当前设备的进度控制暂不可用" else "此播放器暂不支持跳转进度", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    if (state.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = RemoteColors.Action)
                        Text("正在发送操作…", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    }
                }
                state.sessions.isNotEmpty() -> ManagementEmpty(ManagementIcons.Play, "暂时无法选择播放器", "播放来源尚不明确，请刷新或重新打开播放器")
                !available -> ManagementEmpty(ManagementIcons.Play, "设备未连接", "连接设备后即可查看和控制播放")
                !state.loaded && state.refreshError != null -> ManagementEmpty(ManagementIcons.Play, "暂时无法读取播放内容", "可点击右上方刷新重试")
                !state.loaded -> Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = RemoteColors.Action)
                    Text("正在读取播放内容…", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                }
                else -> ManagementEmpty(ManagementIcons.Play, "暂无播放内容", "在被控设备上打开音乐或视频后，可点击右上方刷新")
            }
            if (session == null) volumeControls()
            state.refreshError?.let { Text(it, color = RemoteColors.Warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("media-refresh-error")) }
            state.error?.let { Text(it, color = RemoteColors.Warning, style = MaterialTheme.typography.bodySmall) }
            state.message?.let { Text(it, color = RemoteColors.Secondary, style = MaterialTheme.typography.bodySmall) }
        }
        if (session != null) {
            Panel {
                TextButton({ precise = !precise }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("media-precise-toggle")) {
                    Text(if (precise) "收起精确跳转" else "跳转到指定时间")
                }
                if (precise) {
                    val requested = parsePlaybackTime(target)
                    val valid = requested != null && (duration == null || requested <= duration)
                    OutlinedTextField(target, { target = it }, label = { Text("跳转时间（分:秒）") }, singleLine = true,
                        isError = target.isNotBlank() && !valid,
                        supportingText = { Text(if (target.isNotBlank() && requested == null) "请输入有效的分:秒或时:分:秒"
                            else if (target.isNotBlank() && !valid) "目标位置不能超过播放器报告时长" else "例如 12:30，也支持 时:分:秒") },
                        modifier = Modifier.fillMaxWidth().testTag("media-seek-position"))
                    ActionButton("跳转", canSeek && valid) { requested?.let(::seek) }
                }
            }
            if (state.customActionAvailable && session.customActions.isNotEmpty()) Panel {
                SectionTitle("更多播放操作")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    session.customActions.forEach { action ->
                        OutlinedButton({ onCustomAction(action.id) }, enabled = available && !state.busy && session.customActions.count { it.id == action.id } == 1,
                            modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text(action.name.ifBlank { action.id }) }
                    }
                }
            }
            Panel {
                TextButton({ details = !details }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (details) "收起播放器详情" else "播放器详情") }
                if (details) {
                    InfoRow("应用包名", session.packageName.orEmpty())
                    session.sessionTag?.let { InfoRow("播放会话", it) }
                }
            }
        }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("切换播放器") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            state.sessions.forEach { candidate -> candidate.selection()?.let { selection ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !state.busy && state.sessions.count { it.selection() == selection } == 1) {
                    onChoose(selection); choosing = false
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(state.selected == selection, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(candidate.playerName(), style = MaterialTheme.typography.bodyLarge)
                        Text(candidate.description ?: candidate.sessionTag ?: "未提供内容标题", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    }
                }
            } }
        }
    }, confirmButton = { TextButton({ choosing = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") } })
}
