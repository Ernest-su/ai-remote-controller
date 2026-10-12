package ernest.airc

import kotlinx.serialization.Serializable
@Serializable
data class StorageTotals(val files: Long, val logicalBytes: Long, val allocatedBytes: Long)
@Serializable
data class StorageLargeFile(val path: String, val category: String, val logicalBytes: Long, val allocatedBytes: Long)
@Serializable
data class StorageScanError(val path: String, val message: String)
@Serializable
data class StorageReport(
    val root: String, val path: String, val totals: StorageTotals, val categories: Map<String, StorageTotals>,
    val largestFiles: List<StorageLargeFile>, val entriesVisited: Long, val directories: Long,
    val duplicateFiles: Long, val repeatedDirectories: Long, val skippedLinks: Long, val skippedSpecial: Long,
    val skippedFilesystems: Long, val errorCount: Long, val errors: List<StorageScanError>, val changedDirectories: Long,
    val truncated: Boolean, val complete: Boolean, val snapshot: Boolean,
)
data class StorageAnalysisState(val busy: Boolean = false, val root: String? = null, val path: String = "", val phase: String = "idle", val visited: Long = 0, val limit: Long = 100000, val report: StorageReport? = null, val error: String? = null)
fun storageCategoryTitle(category: String) = when(category) {
    "apk" -> "APK"; "images" -> "图片"; "video" -> "视频"; "audio" -> "音频"; "archives" -> "压缩文件"
    "documents" -> "文档"; "logs" -> "日志后缀"; "temporary" -> "临时后缀"; else -> "其他"
}
