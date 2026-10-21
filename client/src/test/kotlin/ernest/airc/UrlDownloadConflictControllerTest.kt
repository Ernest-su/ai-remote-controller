package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test

class UrlDownloadConflictControllerTest {
    private val conflict = """{"conflict":{"root":"downloads","path":"file.bin","name":"file.bin","suggestedName":"file (1).bin","canOverwrite":true}}"""
    private val proposal = """{"id":"approval","kind":"overwrite","root":"downloads","path":"file.bin","exists":true,"sizeBytes":4}"""

    @Test fun overwriteWaitsForApprovalAndRejectingDoesNotStartAnotherDownload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<String>()
        val approvals = mutableListOf<JsonObject>()
        val controller = UrlDownloadController(scope, approve = { approvals += it; """{"id":"approval"}""" }) { tool, _ ->
            calls += tool
            if (tool == "files.prepare_overwrite") proposal else conflict
        }
        try {
            controller.editUrl("https://example.test/file.bin"); controller.start(true)
            assertNull(controller.state.value.error); assertNotNull(controller.state.value.conflict)
            controller.start(true, overwrite = true)
            assertNotNull(controller.state.value.overwriteProposal); assertTrue(approvals.isEmpty())
            assertEquals(listOf("files.download_url", "files.prepare_overwrite"), calls)
            controller.confirmOverwrite(false)
            assertEquals(false, approvals.single()["accepted"]?.jsonPrimitive?.boolean)
            assertFalse(controller.state.value.busy); assertEquals("cancelled", controller.state.value.phase)
            assertEquals(2, calls.size)
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun cancellationBeforeDownloadingOnlyRejectsTheProposal() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val approvals = mutableListOf<JsonObject>()
        val controller = UrlDownloadController(scope, approve = { approvals += it; "{}" }) { tool, _ ->
            if (tool == "files.prepare_overwrite") proposal else conflict
        }
        try {
            controller.editUrl("https://example.test/file.bin"); controller.start(true)
            controller.start(true, overwrite = true); controller.cancel()
            assertFalse(controller.state.value.busy); assertNull(controller.state.value.error)
            assertEquals("cancelled", controller.state.value.phase)
            assertEquals(false, approvals.single()["accepted"]?.jsonPrimitive?.boolean)
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun conflictFromAnotherRootIsRejectedWithoutOfferingAnyAction() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = UrlDownloadController(scope) { _, _ -> conflict.replace("downloads", "full") }
        try {
            controller.editUrl("https://example.test/file.bin"); controller.start(true)
            assertNull(controller.state.value.conflict); assertNotNull(controller.state.value.error)
        } finally { scope.cancel() }
        Unit
    }
}
