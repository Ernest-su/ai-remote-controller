package ernest.airc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteVolumeSlider(volume: RemoteVolume?, connected: Boolean, onSet: (Float) -> Unit,
    modifier: Modifier = Modifier, setting: Boolean = false) {
    var dragging by remember(connected, volume == null) { mutableStateOf<Float?>(null) }
    var submitted by remember(connected, volume == null) { mutableStateOf<Float?>(null) }
    LaunchedEffect(volume, setting, submitted) { if (!setting) submitted = null }
    val enabled = connected && volume != null && !setting
    val colors = SliderDefaults.colors(
        thumbColor = RemoteColors.Action, activeTrackColor = RemoteColors.Action,
        inactiveTrackColor = RemoteColors.Outline,
        disabledThumbColor = RemoteColors.Disabled, disabledActiveTrackColor = RemoteColors.Disabled,
        disabledInactiveTrackColor = RemoteColors.Divider,
    )
    val displayedFraction = dragging ?: submitted ?: volume?.fraction
    Column(modifier.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (connected && displayedFraction != null) "${(displayedFraction * 100).roundToInt()}%" else "—",
            style = MaterialTheme.typography.bodySmall,
            color = RemoteColors.Secondary,
            maxLines = 1,
            modifier = Modifier.testTag("remote-volume-value"),
        )
        Slider(
            value = displayedFraction ?: 0f,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { submitted = it; onSet(it) }
                dragging = null
            },
            enabled = enabled,
            colors = colors,
            thumb = {
                Box(Modifier.size(RemoteDimensions.VolumeThumbSize)
                    .background(if (connected && volume != null) RemoteColors.Action else RemoteColors.Disabled, CircleShape))
            },
            track = { state ->
                SliderDefaults.Track(state, Modifier.height(RemoteDimensions.VolumeTrackHeight),
                    enabled = enabled, colors = colors, thumbTrackGapSize = 0.dp, drawStopIndicator = null)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("remote-volume-slider")
                .semantics { contentDescription = if (volume == null) "媒体音量暂不可用" else "媒体音量" },
        )
    }
}
