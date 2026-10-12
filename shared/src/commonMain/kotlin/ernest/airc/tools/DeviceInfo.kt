package ernest.airc

import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val androidRelease: String? = null,
    val sdk: Int? = null,
    val abis: List<String>? = null,
    val screen: DeviceScreenSize? = null,
    val storage: DeviceStorage? = null,
)

@Serializable
data class DeviceScreenSize(val width: Int, val height: Int, val source: String? = null)

@Serializable
data class DeviceStorage(val path: String, val totalBytes: Long, val usedBytes: Long, val availableBytes: Long, val mountPoint: String? = null)
