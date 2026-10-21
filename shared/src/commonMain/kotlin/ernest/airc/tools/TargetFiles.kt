package ernest.airc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TargetFileEntry(val name: String, val kind: String, val sizeBytes: Long? = null,
    val symlink: Boolean = false, val linkTarget: String? = null, val linkError: String? = null, val targetName: String? = null) {
    val isSymbolicLink: Boolean get() = symlink || kind == "symlink"
}
@Serializable
data class TargetFilesPage(val root: String, val path: String, val entries: List<TargetFileEntry>, val nextCursor: String? = null, val readOnly: Boolean = false)
@Serializable
data class TargetFileStat(val root: String, val path: String, val kind: String, val sizeBytes: Long? = null,
    val symlink: Boolean = false, val linkTarget: String? = null, val linkError: String? = null, val targetName: String? = null)

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
    val readOnly: Boolean = false,
)

fun validTargetFileName(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." &&
    name.length <= 255 && name.encodeToByteArray().size <= 255 && '/' !in name && name.none { it.isISOControl() }

@Serializable
data class FileArtifact(val id: String, val path: String, val mimeType: String, val sizeBytes: Long,
    val name: String, val sha256: String, val expiresInSeconds: Int)

@Serializable
data class TransferProgress(val id: String, val direction: String, val name: String, val sizeBytes: Long,
    val bytesTransferred: Long, val phase: String, val sha256: String? = null, val error: String? = null, val dataPath: String, val root: String = "downloads", val unit: String = "bytes", val result: JsonElement? = null)


data class FileTransferState(val operation: String = "upload", val sourceName: String? = null, val sourceSize: Long? = null,
    val running: Boolean = false, val phase: String = "idle", val bytes: Long = 0, val total: Long? = null,
    val name: String = "", val root: String = "inbox", val path: String = "", val error: String? = null, val readyToSave: Boolean = false, val proposal: FileActionProposal? = null, val unit: String = "bytes", val result: JsonElement? = null, val noticeId: String? = null)
fun fileRootTitle(root: String): String = when(root) { "inbox" -> "服务收件箱"; "full" -> "全盘 /"; else -> "Downloads" }

@Serializable
data class FileActionProposal(val id: String, val kind: String, val root: String, val path: String, val recursive: Boolean = false, val exists: Boolean, val sizeBytes: Long? = null, val directory: Boolean = false, val approvalRequired: Boolean = true, val symlink: Boolean = false)

fun fileLinkError(code: String?): String = when (code) {
    "link_missing" -> "链接目标不存在"
    "link_outside_root" -> "链接目标超出当前浏览范围"
    "link_loop" -> "链接循环或层数过多"
    "link_permission" -> "没有权限访问链接目标"
    "link_special" -> "不支持访问特殊链接或特殊文件"
    "link_not_directory" -> "链接路径中包含非目录项"
    "link_invalid" -> "链接目标路径无效"
    "link_changed" -> "链接目标已变化，请刷新"
    else -> "链接目标暂时不可访问"
}
