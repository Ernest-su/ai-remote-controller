package ernest.airc

import kotlinx.serialization.Serializable

@Serializable
data class UrlFileInfo(val name: String, val sizeBytes: Long? = null)

data class UrlDownloadState(
    val url: String = "", val name: String = "", val root: String = "downloads", val directory: String = "",
    val busy: Boolean = false, val phase: String = "idle", val bytes: Long = 0, val total: Long? = null,
    val destination: String? = null, val error: String? = null,
    val noticeId: String? = null,
    val conflict: DownloadNameConflict? = null, val overwriteProposal: FileActionProposal? = null,
    val infoLoading: Boolean = false, val infoReady: Boolean = false, val fileSize: Long? = null, val infoError: String? = null,
)

@Serializable
data class DownloadNameConflict(val root: String, val path: String, val name: String, val suggestedName: String, val canOverwrite: Boolean)
