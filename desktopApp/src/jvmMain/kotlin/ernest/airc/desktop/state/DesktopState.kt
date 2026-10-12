package ernest.airc.desktop

import ernest.airc.*

data class DesktopState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val submitting: Boolean = false,
    val touchBusy: Boolean = false,
    val status: String = "请选择 ADB 设备或连接局域网服务",
    val output: String = "",
    val deviceId: String? = null,
    val name: String = "未连接设备",
    val host: String = "",
    val port: Int = 9876,
    val runtimePort: Int = 9876,
    val serial: String? = null,
    val adbDevices: List<String> = emptyList(),
    val version: String? = null,
    val uid: Int? = null,
    val tools: List<String> = emptyList(),
    val preferRoot: Boolean = false,
    val modelConfigured: Boolean = false,
    val apps: List<InstalledApp> = emptyList(),
    val appsLoading: Boolean = false,
    val appsError: String? = null,
    val deviceInfo: DeviceInfo? = null,
    val feedback: RemoteFeedback = RemoteFeedback(),
    val media: String = "未连接设备",
)
