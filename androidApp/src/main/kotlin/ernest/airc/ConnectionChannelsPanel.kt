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

@Composable
internal fun ConnectionChannelsPanel(state: RemoteUiState, model: RemoteViewModel) {
    val adb by model.adbStatus.collectAsState()
    ConnectionStatusSummary(state.connected, state.reconnecting || state.busy, adb == "已连接",
        state.busy || state.reconnecting, model::cancelConnection, state.status)
}

@Composable
internal fun ConnectionStatusSummary(connected: Boolean, connecting: Boolean, adbConnected: Boolean,
    cancellable: Boolean, cancel: () -> Unit, status: String = "",
) {
    Panel(Modifier.testTag("connection-summary")) {
        SectionTitle("当前连接")
        ConnectionStatusRow("遥控服务", connected, if (connected) "已连接" else if (connecting) "自动连接中" else "未连接")
        ConnectionStatusRow("ADB", adbConnected, if (adbConnected) "已连接" else "按需自动连接")
        if (status.isNotBlank()) Text(status, modifier = Modifier.testTag("device-connection-status"),
            style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        if (cancellable) TextButton(cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消连接") }
    }
}

@Composable
private fun ConnectionStatusRow(label: String, available: Boolean, description: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics(mergeDescendants = true) {
        contentDescription = "$label，$description"
    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.size(RemoteDimensions.ConnectionStatusDot).background(
            if (available) RemoteColors.Brand else RemoteColors.Warning, CircleShape).testTag("connection-dot-$label"))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
    }
}
