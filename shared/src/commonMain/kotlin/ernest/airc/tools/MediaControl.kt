package ernest.airc

data class MediaSelection(val packageName: String, val sessionTag: String? = null)
data class MediaControlState(val sessions: List<MediaSession> = emptyList(), val selected: MediaSelection? = null,
    val seekAvailable: Boolean = false, val customActionAvailable: Boolean = false, val busy: Boolean = false, val message: String? = null, val error: String? = null,
    val refreshing: Boolean = false, val loaded: Boolean = false, val refreshError: String? = null,
    val transportAvailable: Boolean = false, val manualRefreshing: Boolean = false, val pendingSeekMs: Long? = null)
fun MediaSession.selection(): MediaSelection? = packageName?.let { MediaSelection(it, sessionTag) }
