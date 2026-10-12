package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class ApkInspectionControllerTest {
    private val info = TargetApkInfo("inbox", "fixture.apk", "com.test.app", 1, "fixture", sizeBytes = 10, sha256 = "a".repeat(64), signatures = emptyList(), signatureVerified = false)
    private fun completed(info: TargetApkInfo) = Json.encodeToString(TransferProgress("fixture-task", "apk_inspect", "fixture.apk", info.sizeBytes, info.sizeBytes, "completed", dataPath = "/transfers/fixture-task/data", root = "inbox", result = Json.encodeToJsonElement(info)))
    @Test fun explicitInspectionDoesNotInstallAndRejectsWrongTarget(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<String>()
        var result = info
        val controller = ApkInspectionController(scope) { tool, _ -> calls += tool; completed(result) }
        try {
            controller.inspect("inbox", "fixture.apk", false); assertTrue(calls.isEmpty())
            controller.inspect("inbox", "fixture.apk", true)
            assertEquals(info, controller.state.value.info)
            assertEquals(listOf("apps.apk_info"), calls)
            result = info.copy(path = "other.apk")
            controller.inspect("inbox", "fixture.apk", true)
            assertNull(controller.state.value.info); assertNotNull(controller.state.value.error)
        } finally { scope.cancel() }
    }
    @Test fun cancellationStopsTaskAndPreservesNoInstallationSideEffect(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<String>()
        val controller = ApkInspectionController(scope) { tool, _ ->
            calls += tool
            Json.encodeToString(TransferProgress("fixture-task", "apk_inspect", "fixture.apk", 10, 0, "inspecting", dataPath = "/transfers/fixture-task/data", root = "inbox"))
        }
        try {
            controller.inspect("inbox", "fixture.apk", true)
            assertTrue(controller.state.value.busy)
            controller.cancel(); yield()
            assertFalse(controller.state.value.busy)
            assertEquals("cancelled", controller.state.value.phase)
            assertEquals(listOf("apps.apk_info", "task.cancel"), calls)
        } finally { scope.cancel() }
    }
    @Test fun clearedConnectionDiscardsLateInspection(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val response = CompletableDeferred<String>()
        val controller = ApkInspectionController(scope) { _, _ -> withContext(NonCancellable) { response.await() } }
        try {
            controller.inspect("inbox", "fixture.apk", true)
            assertTrue(controller.state.value.busy)
            controller.clear(); response.complete(completed(info)); yield()
            assertEquals(ApkInspectionState(), controller.state.value)
        } finally { scope.cancel() }
    }
}
