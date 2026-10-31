package ernest.airc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@Composable
internal fun ConnectionBadge(connected: Boolean, connecting: Boolean, deviceName: String, onClick: () -> Unit) {
    var showName by remember(connected, connecting, deviceName) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(connected, connecting, deviceName, lifecycle) {
        if ((connected || connecting) && deviceName.isNotBlank()) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            showName = false
            while (true) { delay(RemoteDimensions.ConnectionLabelIntervalMillis); showName = !showName }
        }
    }
    val status = if (connected) "已连接" else if (connecting) "连接中" else "未连接"
    val text = if ((connected || connecting) && showName && deviceName.isNotBlank()) deviceName else status
    Row(Modifier.width(RemoteDimensions.ConnectionBadgeWidth).heightIn(min = 48.dp).testTag("device-status")
        .clickable(role = Role.Button, onClickLabel = "管理设备", onClick = onClick)
        .semantics { contentDescription = if ((connected || connecting) && deviceName.isNotBlank()) "$status，$deviceName，管理设备" else "$status，管理设备" }
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
        Box(Modifier.size(RemoteDimensions.ConnectionStatusDot).background(
            if (connected) RemoteColors.Brand else RemoteColors.Warning, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
