package ernest.airc

/** Presentation copies never alter the server's original paginated order/cursor. */
fun visibleTargetFiles(entries: List<TargetFileEntry>, query: String, kind: String, order: String): List<TargetFileEntry> {
    val filtered = entries.filter { entry ->
        entry.name.contains(query.trim(), ignoreCase = true) && when (kind) {
            "folders" -> entry.kind == "directory"
            "files" -> entry.kind == "file"
            else -> true
        }
    }
    val secondary = when (order) {
        "size" -> compareByDescending<TargetFileEntry> { it.sizeBytes ?: -1 }.thenBy { it.name.lowercase() }.thenBy { it.name }
        "descending" -> compareByDescending<TargetFileEntry> { it.name.lowercase() }.thenByDescending { it.name }
        else -> compareBy<TargetFileEntry> { it.name.lowercase() }.thenBy { it.name }
    }
    return filtered.sortedWith(compareBy<TargetFileEntry> { if (it.kind == "directory") 0 else 1 }.then(secondary))
}

fun managementFileSize(bytes: Long?): String {
    if (bytes == null) return "大小未知"
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    var unit = "B"
    for (suffix in listOf("KB", "MB", "GB", "TB")) {
        value /= 1024; unit = suffix
        if (value < 1024) break
    }
    val tenths = kotlin.math.round(value * 10).toLong()
    return "${tenths / 10}${if (tenths % 10 == 0L) "" else ".${tenths % 10}"} $unit"
}

fun fileAncestorPaths(path: String): List<String> = listOf("") + path.split('/').filter { it.isNotEmpty() }
    .runningFold("") { parent, name -> if (parent.isEmpty()) name else "$parent/$name" }.drop(1)
