package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun DesktopDevices(state: DesktopState, controller: DesktopController) = PageContent(Modifier.testTag("devices-page")) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("9876") }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var remotePort by rememberSaveable { mutableStateOf("9876") }
    val validRemotePort = remotePort.toIntOrNull()?.takeIf { it in 1..65535 }
    state.deviceId?.let { id ->
        Panel {
            SectionTitle(state.name)
            DeviceNameEditor(id, state.name, !state.busy, controller::renameDevice)
        }
    }
    Panel {
        SectionTitle("宿主机 ADB 设备", "使用宿主机已授权的 adb；连接后自动建立专属端口转发。")
        ActionButton("刷新 ADB 设备", !state.busy, controller::refreshAdb)
        state.adbDevices.forEach { serial ->
            Text(serial, style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ controller.selectAdb(serial, validRemotePort ?: return@OutlinedButton) }, enabled = !state.busy && validRemotePort != null,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("adb-connect-$serial"), shape = MaterialTheme.shapes.small) { Text("连接服务") }
                OutlinedButton({ controller.deploy(serial, validRemotePort ?: return@OutlinedButton) }, enabled = !state.busy && validRemotePort != null,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("构建 / 部署") }
            }
        }
        OutlinedTextField(remotePort, { remotePort = it }, label = { Text("目标服务端口") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("TCP ADB 地址（IP:端口）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        ActionButton("连接 ADB", !state.busy && endpoint.isNotBlank()) { controller.connectAdb(endpoint) }
    }
    Panel {
        SectionTitle("局域网服务", "已有服务可直接连接，无需 ADB。")
        OutlinedTextField(host, { host = it }, label = { Text("目标地址") }, modifier = Modifier.fillMaxWidth().testTag("runtime-host"), singleLine = true)
        OutlinedTextField(port, { port = it }, label = { Text("服务端口") }, modifier = Modifier.fillMaxWidth().testTag("runtime-port"), singleLine = true)
        ActionButton("连接服务", !state.busy && host.isNotBlank() && port.toIntOrNull()?.let { it in 1..65535 } == true) { controller.connectLan(host, port.toInt()) }
        OutlinedButton(controller::disconnect, enabled = state.connected && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("断开连接") }
    }
    val diagnostic by controller.diagnostics.state.collectAsState()
    val diagnosticHost = host.ifBlank { state.host }
    val diagnosticPort = if (host.isBlank()) state.port else port.toIntOrNull()
    NetworkDiagnosticsPanel(diagnostic, !state.busy && diagnosticHost.isNotBlank() && diagnosticPort?.let { it in 1..65535 } == true,
        { controller.diagnose(diagnosticHost, diagnosticPort ?: return@NetworkDiagnosticsPanel) }, controller.diagnostics::cancel,
        if (diagnosticHost.isBlank()) "" else "$diagnosticHost:${diagnosticPort ?: ""}")
    if (state.busy) TextButton(controller::cancelConnection) { Text("取消操作") }
    OperationDetails(state.output)
}
