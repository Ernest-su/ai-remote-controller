package ernest.airc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TargetFileEntry(val name: String, val kind: String, val sizeBytes: Long? = null)
@Serializable
data class TargetFilesPage(val root: String, val path: String, val entries: List<TargetFileEntry>, val nextCursor: String? = null)
@Serializable
data class TargetFileStat(val root: String, val path: String, val kind: String, val sizeBytes: Long? = null)

data class FileBrowserState(
    val path: String = "",
    val entries: List<TargetFileEntry> = emptyList(),
    val nextCursor: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val selected: TargetFileStat? = null,
    val root: String = "downloads",
    val contentRevision: Long = 0,
)

fun validTargetFileName(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." &&
    name.length <= 255 && name.encodeToByteArray().size <= 255 && '/' !in name && name.none { it.isISOControl() }

@Serializable
data class FileArtifact(val id: String, val path: String, val mimeType: String, val sizeBytes: Long,
    val name: String, val sha256: String, val expiresInSeconds: Int)

@Serializable
data class TransferProgress(val id: String, val direction: String, val name: String, val sizeBytes: Long,
    val bytesTransferred: Long, val phase: String, val sha256: String? = null, val error: String? = null, val dataPath: String, val root: String = "downloads", val unit: String = "bytes", val result: JsonElement? = null)


data class FileTransferState(val sourceName: String? = null, val sourceSize: Long? = null,
    val running: Boolean = false, val phase: String = "idle", val bytes: Long = 0, val total: Long? = null,
    val name: String = "", val root: String = "inbox", val path: String = "", val error: String? = null, val readyToSave: Boolean = false, val proposal: FileActionProposal? = null, val unit: String = "bytes", val result: JsonElement? = null)
fun fileRootTitle(root: String): String = when(root) { "inbox" -> "服务收件箱"; "full" -> "全盘 /"; else -> "Downloads" }

@Serializable
data class FileActionProposal(val id: String, val kind: String, val root: String, val path: String, val recursive: Boolean = false, val exists: Boolean, val sizeBytes: Long? = null, val directory: Boolean = false, val approvalRequired: Boolean = true)
