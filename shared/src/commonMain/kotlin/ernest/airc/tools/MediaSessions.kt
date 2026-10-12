package ernest.airc

import kotlinx.serialization.Serializable

@Serializable
data class MediaSessions(val sessions: List<MediaSession> = emptyList(), val seekAvailable: Boolean = false, val customActionAvailable: Boolean = false, val source: String? = null, val bridgeError: String? = null, val userId: Int? = null)

/** Reported snapshot; positions are not extrapolated and unknown values stay null. */
@Serializable
data class MediaSession(
    val packageName: String? = null,
    val active: Boolean = false,
    val state: Int? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val updatedAtElapsedMs: Long? = null,
    val seekSupported: Boolean? = null,
    val description: String? = null,
    val sessionTag: String? = null,
    val customActions: List<MediaCustomAction> = emptyList(),
)

@Serializable
data class MediaCustomAction(val id: String, val name: String)
