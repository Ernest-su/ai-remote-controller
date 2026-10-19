package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
internal fun TvInputFocusPrompt(visible: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (!visible) return
    Surface(onClick = onOpen, modifier = modifier.widthIn(max = RemoteDimensions.Width).fillMaxWidth()
        .heightIn(min = 48.dp).testTag("tv-input-focus-prompt"),
        shape = MaterialTheme.shapes.small, color = RemoteColors.Surface,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_keypad), null, Modifier.size(24.dp), tint = RemoteColors.Action)
            Column(Modifier.weight(1f)) {
                Text("输入文字", style = MaterialTheme.typography.bodyLarge, color = RemoteColors.Action)
                Text("电视输入框已就绪", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
            }
            Icon(painterResource(R.drawable.ic_right), null, Modifier.size(24.dp), tint = RemoteColors.Secondary)
        }
    }
}
