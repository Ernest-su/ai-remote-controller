package ernest.airc

data class MediaSelection(val packageName: String, val sessionTag: String? = null)
data class MediaControlState(val sessions: List<MediaSession> = emptyList(), val selected: MediaSelection? = null,
    val seekAvailable: Boolean = false, val customActionAvailable: Boolean = false, val busy: Boolean = false, val message: String? = null, val error: String? = null)
fun MediaSession.selection(): MediaSelection? = packageName?.let { MediaSelection(it, sessionTag) }
