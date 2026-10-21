package ernest.airc

data class BatchTransferState(val names: List<String> = emptyList(), val index: Int = 0, val running: Boolean = false,
    val paused: Boolean = false, val message: String? = null, val bytes: Long = 0, val total: Long = 0, val error: String? = null,
    val readyToSave: Boolean = false, val target: String? = null, val noticeId: String? = null,
    val downloadMode: BatchDownloadMode = BatchDownloadMode.ZIP, val saving: Boolean = false, val savedNames: List<String> = emptyList(), val uploadSelection: Boolean = false)

fun validBatchArchiveName(name: String) = validTargetFileName(name) && '\\' !in name && ':' !in name

enum class BatchDownloadMode(val title: String) { ZIP("打包为 ZIP"), INDIVIDUAL("逐个下载") }

fun suggestedDownloadName(name: String, exists: (String) -> Boolean): String {
    require(validBatchArchiveName(name))
    if (!exists(name)) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 && name.substring(it).encodeToByteArray().size < 240 }
    val stem = if (dot == null) name else name.substring(0, dot)
    val extension = if (dot == null) "" else name.substring(dot)
    for (index in 1..9999) {
        val suffix = " ($index)$extension"
        var prefix = stem
        while ((prefix + suffix).encodeToByteArray().size > 255) {
            // Drop an entire supplementary character when trimming a UTF-16 name.
            prefix = prefix.dropLast(if (prefix.last().isLowSurrogate() && prefix.length > 1 && prefix[prefix.lastIndex - 1].isHighSurrogate()) 2 else 1)
        }
        val candidate = prefix + suffix
        if (!exists(candidate)) return candidate
    }
    error("同名文件过多，请选择其他文件夹")
}
