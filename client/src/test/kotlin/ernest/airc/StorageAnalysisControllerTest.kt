package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class StorageAnalysisControllerTest {
    private val report = StorageReport("inbox", "folder", StorageTotals(1, 7, 4096), mapOf("images" to StorageTotals(1, 7, 4096)), listOf(StorageLargeFile("folder/a.jpg", "images", 7, 4096)), 1, 1, 0, 0, 0, 0, 0, 0, emptyList(), 0, false, true, false)
    private fun task(phase: String = "completed", value: StorageReport = report) = Json.encodeToString(TransferProgress("scan", "storage_scan", "folder", 100000, 1, phase, dataPath = "/transfers/scan/data", root = "inbox", unit = "items", result = if(phase == "completed") Json.encodeToJsonElement(value) else null))
    @Test fun analysisIsExplicitReadonlyAndRejectsMismatchedScope(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<String>(); var value = report
        val controller = StorageAnalysisController(scope) { tool, _ -> calls += tool; task(value = value) }
        try {
            controller.analyze("inbox", "folder", false); assertTrue(calls.isEmpty())
            controller.analyze("inbox", "folder", true); assertEquals(report, controller.state.value.report)
            assertEquals(listOf("storage.analyze"), calls)
            value = report.copy(path = "other"); controller.analyze("inbox", "folder", true)
            assertNull(controller.state.value.report); assertNotNull(controller.state.value.error)
        } finally { scope.cancel() }
    }
    @Test fun cancellationStopsPollingAndRequestsTaskCancellation(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<String>()
        val controller = StorageAnalysisController(scope) { tool, _ -> calls += tool; task("scanning") }
        try {
            controller.analyze("inbox", "folder", true); assertTrue(controller.state.value.busy)
            controller.cancel(); yield()
            assertEquals("cancelled", controller.state.value.phase)
            assertEquals(listOf("storage.analyze", "task.cancel"), calls)
        } finally { scope.cancel() }
    }
    @Test fun oldConnectionResponseDoesNotPopulateClearedState(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val response = CompletableDeferred<String>()
        val controller = StorageAnalysisController(scope) { _, _ -> withContext(NonCancellable) { response.await() } }
        try {
            controller.analyze("inbox", "folder", true); controller.clear(); response.complete(task()); yield()
            assertEquals(StorageAnalysisState(), controller.state.value)
        } finally { scope.cancel() }
    }
    @Test fun delayedCancellationKeepsScanBusyUntilCleanupFinishes(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cancellation = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val controller = StorageAnalysisController(scope) { tool, _ ->
            calls += tool
            if(tool == "task.cancel") cancellation.await()
            task("scanning")
        }
        try {
            controller.analyze("inbox", "folder", true)
            controller.cancel(); yield()
            assertTrue(controller.state.value.busy)
            assertEquals("cancelling", controller.state.value.phase)
            controller.analyze("inbox", "another", true)
            assertEquals(listOf("storage.analyze", "task.cancel"), calls)
            cancellation.complete(Unit); yield()
            assertFalse(controller.state.value.busy)
            assertEquals("cancelled", controller.state.value.phase)
        } finally { cancellation.complete(Unit); scope.cancel() }
    }
    @Test fun deletionUpdatesOnlyItsReportAndMarksTotalsAsOld(): Unit = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val calls=mutableListOf<String>()
        val controller=StorageAnalysisController(scope) {tool,_->calls+=tool;task()}
        try {
            controller.analyze("inbox","folder",true)
            controller.fileDeleted("downloads","folder/a.jpg")
            assertEquals(report,controller.state.value.report);assertFalse(controller.state.value.outdated)
            controller.fileDeleted("inbox","folder/a.jpg")
            assertTrue(controller.state.value.report!!.largestFiles.isEmpty())
            assertEquals(report.totals,controller.state.value.report!!.totals)
            assertTrue(controller.state.value.outdated)
            assertEquals(listOf("storage.analyze"),calls)
            controller.analyze("inbox","folder",true)
            assertFalse(controller.state.value.outdated)
            assertEquals(report,controller.state.value.report)
        } finally {scope.cancel()}
    }

}
