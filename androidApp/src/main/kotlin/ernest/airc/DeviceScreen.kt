package ernest.airc

import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Devices(state: RemoteUiState, model: RemoteViewModel) {
    var connectionExpanded by rememberSaveable { mutableStateOf(false) }
    var method by rememberSaveable { mutableStateOf(ConnectionMethod.TCP) }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("5555") }
    var runtimePort by rememberSaveable { mutableStateOf("9876") }
    var code by remember { mutableStateOf("") }
    var connectionPort by rememberSaveable { mutableStateOf("") }
    fun validPort(text: String) = text.toIntOrNull()?.takeIf { it in 1..65535 }
    val diagnostic by model.diagnostics.state.collectAsState()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) model.scanAdbDevices()
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) model.cancelAdbScan()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) model.scanAdbDevices()
        onDispose { lifecycle.removeObserver(observer); model.cancelAdbScan() }
    }
    PageContent(Modifier.testTag("devices-page")) {
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_device_tv), null, Modifier.size(RemoteDimensions.DeviceIcon), tint = RemoteColors.Secondary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    SectionTitle("附近与已保存设备")
                    Text(state.adbScanStatus, style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                }
                IconButton(model::scanAdbDevices, enabled = !state.adbScanning,
                    modifier = Modifier.size(48.dp).testTag("refresh-adb-devices")) {
                    Icon(painterResource(R.drawable.ic_refresh), "重新扫描设备")
                }
            }
            if (state.adbScanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("自动查找同网段 ADB 与遥控服务，点击设备后连接。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        }
        Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small, modifier = Modifier.testTag("unified-device-list")) {
            Column {
                val entries = deviceListEntries(state)
                if (entries.isEmpty()) Text(if (state.adbScanning) "正在寻找设备…" else "暂未找到设备，可重新扫描或手动添加。",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                entries.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(color = RemoteColors.Divider, thickness = 0.5.dp)
                    UnifiedDeviceRow(entry, state, model)
                }
            }
        }
        Panel {
            TextButton(onClick = { connectionExpanded = !connectionExpanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connection-expand")) {
                Text(if (connectionExpanded) "连接目标设备 · 收起" else "连接目标设备 · 展开", style = MaterialTheme.typography.titleMedium)
            }
            if (connectionExpanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(ConnectionMethod.TCP to "TCP", ConnectionMethod.WIRELESS_CODE to "配对码", ConnectionMethod.WIRELESS_QR to "二维码").forEach { (value, label) ->
                        FilterChip(modifier = Modifier.heightIn(min = 48.dp), selected = method == value, enabled = !state.busy, onClick = { method = value; port = if (value == ConnectionMethod.TCP) "5555" else "" }, label = { Text(label, style = MaterialTheme.typography.bodyMedium) })
                    }
                }
                if (method != ConnectionMethod.WIRELESS_QR) {
                    OutlinedTextField(host, { host = it }, label = { Text("目标设备 IP / 主机名") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                    OutlinedTextField(port, { port = it }, label = { Text(if (method == ConnectionMethod.TCP) "ADB 端口" else "目标设备配对端口") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !state.busy)
                    if (method == ConnectionMethod.WIRELESS_CODE) OutlinedTextField(code, { code = it }, label = { Text("六位配对码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !state.busy)
                    if (method == ConnectionMethod.WIRELESS_CODE) {
                        OutlinedTextField(connectionPort, { connectionPort = it }, label = { Text("连接端口（可选）") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !state.busy)
                        Text("留空自动发现；无法发现时，填写无线调试首页的连接端口，与配对端口不同。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(runtimePort, { runtimePort = it }, label = { Text("遥控服务端口") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !state.busy)
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(model::cancelConnection) { Text("取消连接") }
                } else Button(shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                    val targetPort = validPort(runtimePort) ?: return@Button
                    when (method) {
                        ConnectionMethod.TCP -> model.tcp(host, validPort(port) ?: return@Button, targetPort)
                        ConnectionMethod.WIRELESS_CODE -> { model.pair(host, validPort(port) ?: return@Button, code, targetPort, connectionPort.takeIf { it.isNotBlank() }?.let { validPort(it) ?: return@Button }); code = "" }
                        ConnectionMethod.WIRELESS_QR -> model.qr(targetPort)
                    }
                }, enabled = validPort(runtimePort) != null && (method == ConnectionMethod.WIRELESS_QR || (host.isNotBlank() && validPort(port) != null && (method == ConnectionMethod.TCP || (code.matches(Regex("[0-9]{6}")) && (connectionPort.isBlank() || validPort(connectionPort) != null)))))) { Text(if (method == ConnectionMethod.WIRELESS_QR) "显示配对二维码" else "连接并部署") }
                state.qr?.let { qr ->
                    val bitmap = remember(qr) { qr.bitmap().asImageBitmap() }
                    Image(bitmap, "目标设备扫描此二维码完成配对", Modifier.fillMaxWidth().height(260.dp))
                    Text("目标设备：开发者选项 → 无线调试 → 使用二维码配对设备", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text(state.status, style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        OperationDetails(state.output)
        val remembered = state.history.firstOrNull { it.id == state.selected }
        val diagnosticHost = host.ifBlank { remembered?.host.orEmpty() }
        val diagnosticPort = if (host.isBlank()) remembered?.runtimePort else validPort(runtimePort)
        NetworkDiagnosticsPanel(diagnostic, !state.busy && diagnosticHost.isNotBlank() && diagnosticPort != null,
            { model.diagnose(diagnosticHost, diagnosticPort ?: return@NetworkDiagnosticsPanel) }, model.diagnostics::cancel,
            if (diagnosticHost.isBlank()) "" else "$diagnosticHost:${diagnosticPort ?: ""}")
        TextButton(model::disconnect, enabled = state.connected) { Text("断开当前连接") }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnifiedDeviceRow(entry: DeviceListEntry, state: RemoteUiState, model: RemoteViewModel) {
    var showManagement by remember { mutableStateOf(false) }
    val connected = state.connected && entry.history.any { it.id == state.selected }
    Column(Modifier.fillMaxWidth().testTag("device-row-${entry.key}")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_device_tv), null, Modifier.size(RemoteDimensions.DeviceIcon), tint = if (connected) RemoteColors.Action else RemoteColors.Secondary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                Text(entry.host, style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entry.history.isNotEmpty()) DeviceSource(R.drawable.ic_history, "已保存")
                    if (entry.service != null) DeviceSource(R.drawable.ic_service, "局域网服务")
                    if (entry.adbHost != null) DeviceSource(R.drawable.ic_adb, "ADB · 5555")
                }
                if (connected) Text("当前连接", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Action)
            }
            TextButton(onClick = {
                when {
                    entry.service != null -> model.discovered(entry.service, entry.history.firstOrNull { it.adbPort != null }?.adbPort ?: if (entry.adbHost != null) 5555 else null)
                    entry.history.isNotEmpty() -> model.reconnect(entry.history.first())
                    entry.adbHost != null -> model.tcp(entry.adbHost, 5555, 9876)
                }
            }, enabled = !state.busy && !connected, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (connected) "已连接" else "连接") }
            if (entry.history.isNotEmpty()) IconButton({ showManagement = !showManagement }, Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_more), "管理 ${entry.name}")
            }
        }
        if (showManagement) Column(Modifier.padding(horizontal = 16.dp)) {
            entry.history.forEach { device ->
                DeviceNameEditor(device.id, device.visibleName, !state.busy) { model.rename(device.id, it) }
                TextButton({ model.forget(device.id) }, enabled = !state.busy) { Text("忘记已保存记录", color = RemoteColors.Secondary) }
            }
        }
    }
}

@Composable
private fun DeviceSource(icon: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(icon), null, Modifier.size(RemoteDimensions.DeviceSourceIcon), tint = RemoteColors.Secondary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
    }
}
