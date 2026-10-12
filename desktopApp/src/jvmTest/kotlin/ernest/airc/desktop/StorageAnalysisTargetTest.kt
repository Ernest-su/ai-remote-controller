package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class StorageAnalysisTargetTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun liveReadonlyStorageAnalysisDeduplicatesAndReportsPartialLimit(): Unit = runBlocking {
        val serial = System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned storage analysis fixture", !serial.isNullOrBlank())
        val adb = HostAdb(); val target = ShellRuntimeTarget(serial!!); val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = StorageAnalysisController(scope, runtime)
        val folder = "airc-storage-${java.util.UUID.randomUUID()}"
        val path = "${target.inboxPath}/$folder"
        var owned = false
        try {
            target.prepare()
            adb.command("-s", serial, "shell", "test ! -e '$path' && mkdir '$path'"); owned = true
            adb.command("-s", serial, "shell", "mkdir '$path/nested' && printf fixture > '$path/nested/photo.JPG' && ln '$path/nested/photo.JPG' '$path/nested/alias.JPG' && truncate -s 17825792 '$path/large.mp4' && ln -s / '$path/escape'")
            val before = adb.command("-s", serial, "shell", "sha256sum '$path/nested/photo.JPG'")
            val status = runtime.connect("127.0.0.1", checkNotNull(target.port)); assertTrue("storage.analyze" in status.tools)
            controller.analyze("inbox", folder, true)
            val done = withTimeout(30000) { controller.state.first { !it.busy } }
            assertNull(done.error); val report = checkNotNull(done.report)
            assertTrue(report.complete); assertFalse(report.snapshot)
            assertEquals(2L, report.totals.files); assertEquals(17825799L, report.totals.logicalBytes)
            assertEquals(1L, report.duplicateFiles); assertEquals(1L, report.skippedLinks)
            assertEquals("$folder/large.mp4", report.largestFiles.first().path)
            assertEquals(1L, report.categories["images"]?.files)
            assertTrue(report.totals.allocatedBytes < report.totals.logicalBytes)
            var limited = json.decodeFromString<TransferProgress>(runtime.request("tool", "storage.analyze", buildJsonObject { put("root", "inbox"); put("path", folder); put("maxEntries", 1) }))
            withTimeout(10000) { while(limited.phase !in listOf("completed", "failed", "cancelled")) { delay(200); limited = json.decodeFromString(runtime.request("tool", "task.progress", buildJsonObject { put("id", limited.id) })) } }
            assertEquals("completed", limited.phase)
            val partial = json.decodeFromJsonElement<StorageReport>(checkNotNull(limited.result))
            assertTrue(partial.truncated); assertFalse(partial.complete); assertEquals(1L, partial.entriesVisited)
            assertEquals(before, adb.command("-s", serial, "shell", "sha256sum '$path/nested/photo.JPG'"))
            assertFails { runtime.request("tool", "storage.analyze", buildJsonObject { put("root", "inbox"); put("path", "../outside") }) }
        } finally {
            controller.clear(); scope.cancel(); runtime.close()
            try { if(owned) adb.command("-s", serial, "shell", "rm '$path/nested/alias.JPG' '$path/nested/photo.JPG' '$path/large.mp4' '$path/escape' && rmdir '$path/nested' '$path'") }
            finally { target.close() }
        }
    }
}
