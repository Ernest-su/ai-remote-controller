package ernest.airc

import kotlinx.serialization.Serializable

@Serializable
data class MediaSessions(val sessions: List<MediaSession> = emptyList(), val seekAvailable: Boolean = false, val customActionAvailable: Boolean = false, val source: String? = null, val bridgeError: String? = null, val userId: Int? = null, val transportAvailable: Boolean = false)

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
    val appName: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val actions: Long? = null,
)

@Serializable
data class MediaCustomAction(val id: String, val name: String)

fun MediaSession.supportsCommand(command: String): Boolean {
    val action = when (command) { "play" -> 4L; "pause" -> 2L; "previous" -> 16L; "next" -> 32L; else -> return false }
    val declared = actions?.takeIf { it >= 0 } ?: return false
    if (declared and action != 0L) return true
    // A toggle has a safe direction only for a known playing or paused state.
    return declared and 512L != 0L && when (command) {
        "pause" -> state == 3
        "play" -> state in listOf(0, 1, 2)
        else -> false
    }
}
fun MediaSession.playbackLabel(): String = when (state) {
    3 -> "正在播放"; 2 -> "已暂停"; 1 -> "已停止"; 6, 8 -> "正在缓冲"; 4, 5 -> "正在定位"; 7 -> "播放出错"; else -> "播放状态未知"
}
fun MediaSession.playerName(): String = appName?.takeIf { it.isNotBlank() } ?: "媒体播放器"
