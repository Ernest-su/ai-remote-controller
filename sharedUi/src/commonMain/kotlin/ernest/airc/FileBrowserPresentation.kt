package ernest.airc

/** Filters only the loaded directory snapshot; never changes the server paging cursor. */
internal enum class FileCategory(val title: String) {
    ALL("全部文件"), FOLDER("文件夹"), IMAGE("图片"), VIDEO("视频"), AUDIO("音频"), DOCUMENT("文档"), APK("安装包"), OTHER("其他");
}

internal fun fileCategory(entry: TargetFileEntry): FileCategory {
    if (entry.kind == "directory") return FileCategory.FOLDER
    if (entry.kind != "file") return FileCategory.OTHER
    when(filePreviewKind(entry)) {
        FilePreviewKind.IMAGE -> return FileCategory.IMAGE
        FilePreviewKind.VIDEO -> return FileCategory.VIDEO
        FilePreviewKind.AUDIO -> return FileCategory.AUDIO
        FilePreviewKind.TEXT -> return FileCategory.DOCUMENT
        null -> Unit
    }
    return when (entry.name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif" -> FileCategory.IMAGE
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts" -> FileCategory.VIDEO
        "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus" -> FileCategory.AUDIO
        "txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "json", "xml", "log" -> FileCategory.DOCUMENT
        "apk" -> FileCategory.APK
        else -> FileCategory.OTHER
    }
}
