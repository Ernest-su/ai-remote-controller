package ernest.airc

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun MediaControls(state: RemoteUiState, model: RemoteViewModel) {
    val control by model.mediaControl.state.collectAsState()
    val available = state.connected && "media.status" in state.tools
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val renderedSelection = control.selected
    val volume by model.remoteVolume.state.collectAsState()
    val volumeSetting by model.remoteVolume.setting.collectAsState()
    RemoteKeyLifecycle(model.remoteKeys)
    DisposableEffect(model) {
        model.setMediaControlVisible(true)
        onDispose { model.setMediaControlVisible(false); model.mediaControl.stopRefreshing() }
    }
    LaunchedEffect(available, lifecycle) {
        if (available) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { model.mediaControl.observe() }
    }
    CompositionLocalProvider(LocalRemoteKeySender provides model.remoteKeys) {
    MediaControlPanel(control, available, { model.mediaControl.refresh(available) }, model.mediaControl::choose,
        { model.mediaControl.seek(it, available && "media.seek" in state.tools, renderedSelection) },
        { model.mediaControl.customAction(it, available && "media.custom_action" in state.tools, renderedSelection) },
        { model.mediaControl.command(it, available && "media.command" in state.tools, renderedSelection) },
        volumeControls = { PlaybackVolumeControls(volume, state.connected, volumeSetting, model.remoteVolume::setFraction, model::key) })
    }
}

@Composable
internal fun PlaybackVolumeControls(volume: RemoteVolume?, connected: Boolean, setting: Boolean,
    onSet: (Float) -> Unit, onKey: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("media-volume-controls")) {
        Text("媒体音量", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            val enabled = connected && volume != null && !setting
            RemoteIconButton({ onKey("mute") }, enabled, Modifier.size(48.dp), debugName = "mute") {
                Icon(painterResource(R.drawable.ic_mute), "静音", Modifier.size(24.dp))
            }
            RemoteIconButton({ onKey("volume_down") }, enabled, Modifier.size(48.dp), debugName = "volume_down") {
                Icon(painterResource(R.drawable.ic_volume_down), "音量 −", Modifier.size(24.dp))
            }
            RemoteVolumeSlider(volume, connected, onSet, Modifier.weight(1f), setting)
            RemoteIconButton({ onKey("volume_up") }, enabled, Modifier.size(48.dp), debugName = "volume_up") {
                Icon(painterResource(R.drawable.ic_volume_up), "音量 +", Modifier.size(24.dp))
            }
        }
    }
}
