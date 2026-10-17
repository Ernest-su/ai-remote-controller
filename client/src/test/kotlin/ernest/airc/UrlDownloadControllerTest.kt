package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class UrlDownloadControllerTest {
    private fun task(phase: String = "completed", root: String = "downloads", bytes: Long = 4, total: Long? = 4) =
        Json.encodeToString(TransferProgress("url-task", "url_download", "file.bin", 0, bytes, phase,
            dataPath = "/transfers/url-task/data", root = root,
            result = buildJsonObject { put("path", "file.bin"); put("totalBytes", total?.let(::JsonPrimitive) ?: JsonNull) }))

    @Test fun validatesInputAndDefaultsWithoutDependingOnBrowserLocation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val controller = UrlDownloadController(scope) { tool, args -> calls += tool to args; task() }
        try {
            controller.editUrl("file:///etc/passwd"); controller.start(true); assertTrue(calls.isEmpty())
            controller.editUrl("http://localhost/file.bin"); controller.start(false); assertTrue(calls.isEmpty())
            controller.editName("../escape"); controller.start(true); assertTrue(calls.isEmpty())
            controller.editName(""); controller.start(true)
            assertEquals("completed", controller.state.value.phase)
            assertEquals(4L, controller.state.value.bytes)
            assertEquals("downloads", calls.single().second["root"]?.jsonPrimitive?.content)
            assertEquals("", calls.single().second["directory"]?.jsonPrimitive?.content)
            assertNull(calls.single().second["name"])
            assertEquals("http://localhost/file.bin", controller.state.value.url)
        } finally { scope.cancel() }
    }

    @Test fun unknownLengthCanBeCancelledAndCleanupBlocksDuplicateSubmissions() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cleanup = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val controller = UrlDownloadController(scope) { tool, _ ->
            calls += tool
            if(tool == "task.cancel") { cleanup.await(); task("cancelled", total = null) }
            else task("downloading", total = null)
        }
        try {
            controller.editUrl("https://example.org/file.bin"); controller.start(true)
            assertTrue(controller.state.value.busy); assertNull(controller.state.value.total)
            controller.cancel(); yield()
            assertTrue(controller.state.value.busy); assertEquals("cancelling", controller.state.value.phase)
            controller.start(true); assertEquals(listOf("files.download_url", "task.cancel"), calls)
            cleanup.complete(Unit); yield()
            assertEquals("cancelled", controller.state.value.phase); assertFalse(controller.state.value.busy)
        } finally { cleanup.complete(Unit); scope.cancel() }
    }

    @Test fun cancellationDuringCommitReportsActualCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = UrlDownloadController(scope) { tool, _ ->
            task(when(tool) { "task.cancel" -> "committing"; "task.progress" -> "completed"; else -> "downloading" })
        }
        try {
            controller.editUrl("https://example.org/file.bin"); controller.start(true); controller.cancel()
            assertEquals("committing", controller.state.value.phase)
            withTimeout(3000) { while(controller.state.value.busy) delay(20) }
            assertEquals("completed", controller.state.value.phase)
        } finally { scope.cancel() }
    }

    @Test fun rejectsWrongTargetAndLateCompletionAfterClear() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val wrong = UrlDownloadController(scope) { _, _ -> task(root = "full") }
            wrong.editUrl("https://example.org/file.bin"); wrong.start(true)
            assertEquals("failed", wrong.state.value.phase); assertNotNull(wrong.state.value.error)
            val response = CompletableDeferred<String>()
            val late = UrlDownloadController(scope) { tool, _ -> if(tool == "task.cancel") task() else withContext(NonCancellable) { response.await() } }
            late.editUrl("https://example.org/file.bin"); late.start(true); late.clear(); response.complete(task()); yield()
            assertEquals(UrlDownloadState(), late.state.value)
        } finally { scope.cancel() }
    }
}
