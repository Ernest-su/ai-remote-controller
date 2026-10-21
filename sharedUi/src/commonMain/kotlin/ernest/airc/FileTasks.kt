package ernest.airc

fun fileTaskSections(batch: BatchTransferState, transfer: FileTransferState, download: UrlDownloadState,
    storage: StorageAnalysisState, apk: ApkInspectionState, installation: AppInstallationState, media: FileMediaState): List<String> = buildList {
    if (batch.noticeId != null || batch.running || batch.paused || batch.readyToSave || batch.error != null) add("batch")
    if (transfer.phase != "idle" || transfer.proposal != null || transfer.error != null) add(transfer.operation)
    if (download.noticeId != null || download.phase != "idle" || download.error != null) add("url")
    if (storage.noticeId != null || storage.busy || storage.report != null || storage.error != null) add("storage")
    if (apk.noticeId != null || installation.noticeId != null || apk.busy || apk.info != null || apk.error != null || installation.busy || installation.message != null || installation.error != null) add("apk")
    if (media.noticeId != null || media.busy || media.message != null || media.error != null || media.resourceId != null || media.publicationId != null || media.retainedCopies.isNotEmpty()) add("media")
}.distinct()

fun fileTaskTitle(section: String): String = when (section) {
    "batch" -> "文件传输"
    "upload" -> "文件上传"
    "download" -> "文件下载"
    "delete" -> "文件删除"
    "url" -> "网址下载"
    "storage" -> "存储分析"
    "apk" -> "APK 信息与安装"
    "media" -> "被控设备播放"
    else -> "文件任务"
}

enum class FileTaskType(val title: String) {
    UPLOAD("上传"), DOWNLOAD("下载"), URL("网址下载"), DELETE("删除"), STORAGE("存储分析"), APK("应用"), MEDIA("播放")
}
data class FileTaskGroup(val type: FileTaskType, val sections: List<String>, val count: Int)
fun groupFileTaskSections(sections: List<String>, batchUpload: Boolean = false, apkCount: Int = 1): List<FileTaskGroup> =
    FileTaskType.entries.map { type ->
        val matching=sections.distinct().filter { section -> when(section) {
            "upload" -> FileTaskType.UPLOAD
            "download" -> FileTaskType.DOWNLOAD
            "batch" -> if(batchUpload) FileTaskType.UPLOAD else FileTaskType.DOWNLOAD
            "url" -> FileTaskType.URL
            "delete" -> FileTaskType.DELETE
            "storage" -> FileTaskType.STORAGE
            "apk" -> FileTaskType.APK
            else -> FileTaskType.MEDIA
        } == type }
        FileTaskGroup(type,matching,matching.sumOf { if(it=="apk") apkCount else 1 })
    }
fun fileTaskGroups(batch: BatchTransferState, transfer: FileTransferState, download: UrlDownloadState,
    storage: StorageAnalysisState, apk: ApkInspectionState, installation: AppInstallationState, media: FileMediaState): List<FileTaskGroup> {
    val apkPresent=apk.noticeId!=null || apk.busy || apk.info!=null || apk.error!=null
    val installationPresent=installation.noticeId!=null || installation.busy || installation.message!=null || installation.error!=null
    return groupFileTaskSections(fileTaskSections(batch,transfer,download,storage,apk,installation,media),batch.uploadSelection,
        (if(apkPresent) 1 else 0)+(if(installationPresent) 1 else 0))
}
