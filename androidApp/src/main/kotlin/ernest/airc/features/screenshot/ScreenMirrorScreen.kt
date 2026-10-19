package ernest.airc

import android.view.Surface
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ernest.ascrcpy.scrcpy.ScrcpyState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AndroidScreenMirror(
    state: RemoteUiState, model: RemoteViewModel, fullscreen: Boolean = false,
    onFullscreen: (Boolean) -> Unit = {}, modifier: Modifier = Modifier,
    onRemote: () -> Unit = {}, remoteVisible: Boolean = false,
    preview: AscrcpyPreviewController = model.ascrcpyPreview,
) {
    val quality by preview.quality.collectAsState()
    var input by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (input) ManagementDialog("电视输入", "mirror-tv-input", { input = false }, grouped = true) { TvInputPanel(state, model) }
    val mirror by preview.state.collectAsState()
    val surfaceEpoch by preview.surfaceEpoch.collectAsState()
    val shot by model.screenshots.state.collectAsState()
    // Retiring the view also retires its handle: wait for the replacement callback
    // before starting a new stream, even if the old Surface is still briefly valid.
    var surface by remember(preview, surfaceEpoch) { mutableStateOf<Surface?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle, preview) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) preview.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); preview.stop() }
    }
    val active = mirror !is ScrcpyState.Idle && mirror !is ScrcpyState.Failed
    val available = foreground && state.connected && !state.busy && !shot.loading
    LaunchedEffect(preview, surfaceEpoch, surface, available, mirror) {
        if (available && mirror is ScrcpyState.Idle) surface?.takeIf { it.isValid }?.let(preview::start)
    }
    val size = (mirror as? ScrcpyState.Streaming)?.videoSize
    val ratio = size?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
    Column(modifier.then(if(fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth())) {
        if (!fullscreen) Row(Modifier.fillMaxWidth().testTag("mirror-toolbar"), verticalAlignment = Alignment.CenterVertically) {
            Text("动态预览", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            MirrorToolbarIcon(R.drawable.ic_text, "电视输入", "mirror-tv-input-open") { input = true }
            MirrorToolbarIcon(R.drawable.ic_remote_types, if(remoteVisible) "收起悬浮遥控" else "悬浮遥控", "mirror-toggle-remote", onClick = onRemote)
            MirrorToolbarIcon(R.drawable.ic_fullscreen, "全屏预览", "mirror-fullscreen", active) { onFullscreen(true) }
        }
        if (!fullscreen) FlowRow {
            PreviewQuality.entries.forEach { value ->
                FilterChip(quality == value, { preview.selectQuality(value) }, label = { Text(value.title) }, modifier = Modifier.heightIn(min = 48.dp).padding(end = 8.dp))
            }
        }
        BoxWithConstraints(if(fullscreen) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(RemoteDimensions.MirrorPreviewHeight), contentAlignment = Alignment.Center) {
            val videoWidth = minOf(maxWidth, maxHeight * ratio)
            MirrorSurface(surfaceEpoch, active, ratio, onSurface = { surface = it }, onDestroyed = preview::stop,
                modifier = Modifier.width(videoWidth),
                onTouch = if(available && mirror is ScrcpyState.Streaming) preview::touch else null)
            if (fullscreen) MirrorFullscreenToolbar(remoteVisible, onRemote, { onFullscreen(false) }, Modifier.align(Alignment.TopEnd))
        }
        when(val value = mirror) {
            ScrcpyState.Idle -> if(!state.connected) Text("连接设备后自动开始动态预览。", color = RemoteColors.Secondary)
            is ScrcpyState.Failed -> {
                Text(value.cause.message ?: "动态预览失败", color = RemoteColors.Warning)
                TextButton({ surface?.takeIf { it.isValid }?.let(preview::start) }, enabled = available && surface?.isValid == true,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("mirror-retry")) { Text("重试预览") }
            }
            is ScrcpyState.Streaming -> Unit
            else -> LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun MirrorToolbarIcon(icon: Int, label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp).mirrorControlBacking().testTag(tag)) {
        Icon(painterResource(icon), label, Modifier.size(24.dp), tint = if(enabled) RemoteColors.Action else RemoteColors.Disabled)
    }
}

@Composable
internal fun MirrorFullscreenToolbar(remoteVisible: Boolean, onRemote: () -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MirrorToolbarIcon(R.drawable.ic_remote_types, if (remoteVisible) "收起悬浮遥控" else "悬浮遥控", "mirror-toggle-remote", onClick = onRemote)
        MirrorToolbarIcon(R.drawable.ic_fullscreen_exit, "退出全屏", "mirror-exit-fullscreen", onClick = onExit)
    }
}
