package ernest.airc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val PROTOCOL_VERSION = 1
const val RUNTIME_SERVICE_TYPE = "_airc._tcp."

@Serializable
enum class ConnectionMethod { TCP, WIRELESS_CODE, WIRELESS_QR }

/** Pairing codes and QR passwords must never be persisted. */
@Serializable
data class RememberedDevice(
    val id: String,
    val name: String,
    val method: ConnectionMethod,
    val host: String? = null,
    val adbPort: Int? = null,
    val wirelessGuid: String? = null,
    val runtimePort: Int? = null,
    val lastConnectedAt: Long,
    val displayName: String? = null,
)

@Serializable
data class RuntimeRequest(
    val id: String,
    val type: String,
    val protocol: Int = PROTOCOL_VERSION,
    val tool: String? = null,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val text: String? = null,
    val progress: Boolean = false,
)

@Serializable
data class RuntimeResponse(
    val id: String,
    val ok: Boolean,
    val protocol: Int = PROTOCOL_VERSION,
    val result: String? = null,
    val error: String? = null,
)

/** Optional request-scoped progress; terminal RuntimeResponse remains unchanged. */
@Serializable
data class RuntimeProgress(
    val id: String,
    val type: String = "assistant.state",
    val protocol: Int = PROTOCOL_VERSION,
    val phase: String,
    val text: String = "",
    val tool: String? = null,
)

@Serializable
data class RuntimeStatus(
    val version: String,
    val protocol: Int,
    val deviceId: String,
    val tools: List<String>,
    val name: String? = null,
    val modelConfigured: Boolean = false,
    val uid: Int? = null,
    val textToolScope: Boolean = false,
    val binarySha256: String? = null,
)

/** Pure shared logic; actual storage and networking belong to platform adapters. */
fun rememberDevice(history: List<RememberedDevice>, device: RememberedDevice, previousId: String? = null): List<RememberedDevice> =
    (history.filterNot { it.id == device.id || it.id == previousId } + device.copy(
        displayName = device.displayName ?: history.firstOrNull { it.id == device.id }?.displayName
            ?: history.firstOrNull { it.id == previousId }?.displayName,
    )).sortedByDescending { it.lastConnectedAt }
