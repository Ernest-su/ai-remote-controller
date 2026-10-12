package ernest.airc

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

private fun storageLabel(bytes: Long): String = "${bytes / (1024 * 1024)} MiB"

@Composable
internal fun DeviceInformation(state: RemoteUiState, model: RemoteViewModel) {
    val observation by model.systemObservation.state.collectAsState()
    val system by model.systemActions.state.collectAsState()
    val device = state.history.find { it.id == state.selected }
    PageContent(Modifier.testTag("device-info-page")) {
        if("device.system_state" in state.tools)SystemObservationPanel(observation,state.connected&&!state.busy){model.systemObservation.refresh(state.connected&&!state.busy)}
        Panel {
            SectionTitle("当前设备")
            InfoRow("设备名称", device?.visibleName ?: "尚未选择设备")
            InfoRow("连接状态", if (state.connected) "已连接" else "未连接")
            InfoRow("服务版本", state.version ?: "未知")
            InfoRow("运行权限", runtimePermissionLabel(state))
            device?.let {
                InfoRow("设备地址", it.host ?: "未知")
                InfoRow("遥控服务端口", it.runtimePort?.toString() ?: "未知")
                InfoRow("设备 ID", it.id)
            }
        }
        if("device.system_action" in state.tools || system.requiresVerification) SystemActionPanel(system,state.connected && state.runtimeUid == 0 && !state.busy,device?.visibleName ?: "未知设备",{model.systemActions.prepare(it,state.connected && state.runtimeUid == 0 && !state.busy)},{model.systemActions.pending(state.connected && state.runtimeUid == 0 && !state.busy)},model.systemActions::confirm)
        Panel {
            SectionTitle("系统与硬件", "查询目标设备实际信息；不可用字段显示未知。")
            ActionButton(if (state.deviceInfoLoading) "正在查询…" else "刷新设备信息",
                state.connected && !state.busy && !state.deviceInfoLoading, model::refreshDeviceInfo)
            state.deviceInfoError?.let { Text(it, color = RemoteColors.Warning) }
            state.deviceInfo?.let { info ->
                InfoRow("制造商", info.manufacturer ?: "未知")
                InfoRow("型号", info.model ?: "未知")
                InfoRow("Android 版本", info.androidRelease ?: "未知")
                InfoRow("SDK", info.sdk?.toString() ?: "未知")
                InfoRow("ABI", info.abis?.joinToString() ?: "未知")
                InfoRow("有效屏幕尺寸", info.screen?.let { "${it.width} × ${it.height} px" } ?: "未知")
                InfoRow("数据存储总量", info.storage?.totalBytes?.let(::storageLabel) ?: "未知")
                InfoRow("数据存储已用", info.storage?.usedBytes?.let(::storageLabel) ?: "未知")
                InfoRow("数据存储可用", info.storage?.availableBytes?.let(::storageLabel) ?: "未知")
            } ?: Text(if (state.connected) "点击刷新查询系统与硬件信息。" else "连接设备后可刷新系统与硬件信息。", color = RemoteColors.Secondary)
        }
    }
}
