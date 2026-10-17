package ernest.airc

data class UrlDownloadState(
    val url: String = "", val name: String = "", val root: String = "downloads", val directory: String = "",
    val busy: Boolean = false, val phase: String = "idle", val bytes: Long = 0, val total: Long? = null,
    val destination: String? = null, val error: String? = null,
)
