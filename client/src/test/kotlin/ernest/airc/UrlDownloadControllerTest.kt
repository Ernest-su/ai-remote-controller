package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
    @Test fun metadataFillsNameAndSizeWithoutStartingDownloadAndKeepsManualName() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<String>()
        val calls = mutableListOf<String>()
        val controller = UrlDownloadController(scope) { tool, _ -> calls += tool; started.complete(Unit); result.await() }
        try {
            controller.editUrl("https://example.org/raw")
            assertEquals("raw", controller.state.value.name)
            controller.inspect(true)
            withTimeout(2000) { started.await() }
            controller.editName("我的文件.apk")
            result.complete("""{"name":"电视.apk","sizeBytes":123456}""")
            withTimeout(2000) { controller.state.first { it.infoReady } }
            assertEquals("我的文件.apk", controller.state.value.name)
            assertEquals(123456L, controller.state.value.fileSize)
            assertFalse(controller.state.value.busy)
            assertEquals(listOf("files.url_info"), calls)
            controller.clear()
            controller.editUrl("https://example.org/next")
            controller.inspect(true)
            withTimeout(2000) { controller.state.first { it.infoReady } }
            assertEquals("电视.apk", controller.state.value.name)
        } finally { scope.cancel() }
    }
    @Test fun changedUrlAndClearDiscardLateMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = CompletableDeferred<String>()
        val started = CompletableDeferred<Unit>()
        val controller = UrlDownloadController(scope) { _, args ->
            if (args["url"]?.jsonPrimitive?.content?.endsWith("old") == true) withContext(NonCancellable) { started.complete(Unit); old.await() }
            else """{"name":"new.bin","sizeBytes":null}"""
        }
        try {
            controller.editUrl("https://example.org/old"); controller.inspect(true)
            withTimeout(2000) { started.await() }
            controller.editUrl("https://example.org/new"); controller.inspect(true)
            withTimeout(2000) { controller.state.first { it.infoReady } }
            assertEquals("new.bin", controller.state.value.name)
            assertNull(controller.state.value.fileSize)
            old.complete("""{"name":"stale.bin","sizeBytes":12}"""); yield()
            assertEquals("new.bin", controller.state.value.name)
            controller.clear()
            assertEquals(UrlDownloadState(), controller.state.value)
        } finally { old.complete("{}"); scope.cancel() }
    }
    @Test fun metadataFailureLeavesDownloadDraftUsableAndOfflineDoesNotProbe() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val controller = UrlDownloadController(scope) { _, _ -> calls++; error("fixture network error") }
        try {
            controller.editUrl("https://example.org/file.bin"); controller.inspect(false)
            assertEquals(0, calls)
            controller.inspect(true)
            withTimeout(2000) { controller.state.first { it.infoError != null } }
            assertEquals("file.bin", controller.state.value.name)
            assertFalse(controller.state.value.infoLoading)
            assertEquals("idle", controller.state.value.phase)
        } finally { scope.cancel() }
    }

}
