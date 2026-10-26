package ernest.airc

import kotlinx.serialization.Serializable

enum class FilePreviewKind { IMAGE, VIDEO, AUDIO, TEXT }

fun filePreviewKind(entry: TargetFileEntry): FilePreviewKind? {
    if (entry.kind != "file" || !validTargetFileName(entry.name)) return null
    val name = if (entry.isSymbolicLink) entry.targetName ?: entry.name else entry.name
    if (name.lowercase() in listOf("dockerfile", "makefile", ".gitignore", ".env", ".bashrc", ".zshrc", "license")) return FilePreviewKind.TEXT
    return when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif" -> FilePreviewKind.IMAGE
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "3gp" -> FilePreviewKind.VIDEO
        "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus" -> FilePreviewKind.AUDIO
        "txt", "md", "csv", "json", "xml", "log", "yaml", "yml", "ini", "conf", "properties", "srt", "vtt", "html", "css", "js", "kt", "kts", "java", "rs", "py", "pyw", "sh", "bash", "zsh", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rb", "php", "swift", "m", "mm", "sql", "toml", "env", "gradle", "jsx", "ts", "tsx", "vue", "svelte", "scss", "less", "svg", "bat", "ps1", "r", "lua", "dart" -> FilePreviewKind.TEXT
        else -> null
    }
}

@Serializable
data class FilePreviewResource(val id: String, val root: String, val path: String, val sizeBytes: Long, val dataPath: String)
