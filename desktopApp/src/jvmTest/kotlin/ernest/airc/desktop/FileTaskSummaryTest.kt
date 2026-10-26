package ernest.airc.desktop

import ernest.airc.*
import org.junit.Assert.assertEquals
import org.junit.Test

class FileTaskSummaryTest {
    @Test fun selectionDraftsDoNotCreateTasks() {
        assertEquals(emptyList<String>(), fileTaskSections(
            BatchTransferState(names=listOf("one.txt","two.txt"),uploadSelection=true),
            FileTransferState(sourceName="one.txt"),UrlDownloadState(url="https://example.test/file"),
            StorageAnalysisState(),ApkInspectionState(),AppInstallationState(),FileMediaState()))
    }
    @Test fun completedAndActiveTasksAreCollectedTogether() {
        assertEquals(listOf("batch","download","url","storage","apk","media"),fileTaskSections(
            BatchTransferState(noticeId="batch",message="上传完成"),
            FileTransferState(operation="download",phase="completed",readyToSave=true),
            UrlDownloadState(phase="downloading",busy=true),StorageAnalysisState(noticeId="storage"),
            ApkInspectionState(noticeId="apk"),AppInstallationState(noticeId="installation"),FileMediaState(noticeId="media")))
    }
    @Test fun groupsCountTasksAndKeepAllEmptyTypes() {
        val groups=fileTaskGroups(BatchTransferState(noticeId="batch",uploadSelection=true,names=listOf("one","two")),
            FileTransferState(operation="upload",phase="completed"),UrlDownloadState(),StorageAnalysisState(),
            ApkInspectionState(noticeId="parse"),AppInstallationState(noticeId="install"),FileMediaState())
        assertEquals(FileTaskType.entries.toSet(),groups.map {it.type}.toSet())
        assertEquals(2,groups.single {it.type==FileTaskType.UPLOAD}.count)
        assertEquals(2,groups.single {it.type==FileTaskType.APK}.count)
        assertEquals(0,groups.single {it.type==FileTaskType.DOWNLOAD}.count)
        assertEquals(listOf("batch","upload"),groups.single {it.type==FileTaskType.UPLOAD}.sections)
    }

    @Test fun populatedGroupsComeFirstInStableTypeOrder() {
        val groups = groupFileTaskSections(listOf("media", "apk", "download"))
        assertEquals(listOf(FileTaskType.DOWNLOAD, FileTaskType.APK, FileTaskType.MEDIA), groups.take(3).map { it.type })
        assertEquals(listOf(FileTaskType.UPLOAD, FileTaskType.URL, FileTaskType.DELETE, FileTaskType.STORAGE), groups.drop(3).map { it.type })
        assertEquals(FileTaskType.entries.toList(), groupFileTaskSections(emptyList()).map { it.type })
    }
}
