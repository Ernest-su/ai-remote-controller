package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class AppControlControllerTest {
    private val app = TargetAppInfo("fixture.app", 10, 10001, 1, codePath = "/data/app/fixture", lastUpdateTime = "fixture", system = false)
    private fun snapshot(granted: Boolean = false) = AppControlSnapshot(app.packageName, 10, true, 0, false,
        AppControlCapabilities(true, true, true), listOf(AppPermission("android.permission.CAMERA", "相机", granted, true, true)))
    private fun verify(mode: String): Unit = runBlocking {
        val messages = CopyOnWriteArrayList<JsonObject>()
        var reads = 0
        fun task(phase: String, unit: String = "items") = Json.encodeToString(TransferProgress("task",
            if (mode == "wrong-task") "delete" else "app_control", "fixture.app", 0, if (phase == "completed") 1 else 0, phase,
            dataPath = "/transfers/task/data", root = "apps", unit = unit,
            result = if (phase == "completed") buildJsonObject { put("package", "fixture.app"); put("userId", 10); put("action", "grant"); put("permission", "android.permission.CAMERA"); put("completed", true) } else null))
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                val request = Json.parseToJsonElement(text).jsonObject
                messages += request
                val tool = request["tool"]?.jsonPrimitive?.content
                val result = when {
                    tool == "apps.control_info" -> { reads++; Json.encodeToString(AppControlDetails(app, snapshot(reads > 1 && mode != "unchanged"))) }
                    tool == "apps.prepare_control" -> Json.encodeToString(AppControlProposal("proposal", "app_control", app,
                        if (mode == "wrong-proposal") "clear_data" else "grant", "android.permission.CAMERA", true, false, snapshot()))
                    request["type"]?.jsonPrimitive?.content == "approve_app_control" && request["arguments"]?.jsonObject?.get("accepted") == JsonPrimitive(false) -> "{}"
                    request["type"]?.jsonPrimitive?.content == "approve_app_control" -> task("committing", if (mode == "wrong-unit") "bytes" else "items")
                    tool == "task.progress" -> task("completed", if (mode == "wrong-poll-unit") "bytes" else "items")
                    else -> error("Unexpected request: $request")
                }
                socket.send(buildJsonObject { put("id", request.getValue("id")); put("protocol", 1); put("ok", true); put("result", result) }.toString())
            }
        }))
        server.start()
        val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AppControlController(scope, runtime)
        try {
            runtime.connect(server.hostName, server.port)
            controller.inspect("fixture.app", true)
            withTimeout(3000) { controller.state.first { !it.busy && it.details != null } }
            controller.prepare("grant", "android.permission.CAMERA", true)
            if (mode != "wrong-proposal") {
                withTimeout(3000) { controller.state.first { it.proposal != null } }
                assertFalse(controller.state.value.details!!.state.permissions.single().granted, "Switch must not flip before confirmation")
                if (mode == "disconnect") { runtime.disconnect(); controller.confirm(true) }
                else controller.confirm(mode != "reject")
            }
            val state = withTimeout(3000) { controller.state.first { !it.busy } }
            when (mode) {
                "success" -> { assertNull(state.error); assertNotNull(state.message); assertTrue(state.details!!.state.permissions.single().granted); assertEquals(2, reads) }
                "unchanged" -> { assertNotNull(state.error); assertNull(state.message); assertEquals(2, reads) }
                "reject" -> { assertNull(state.error); assertNull(state.message); assertEquals(1, reads) }
                else -> { assertNotNull(state.error); assertNull(state.message); assertEquals(1, reads) }
            }
            val accepted = messages.filter { it["type"]?.jsonPrimitive?.content == "approve_app_control" && it["arguments"]?.jsonObject?.get("accepted") != JsonPrimitive(false) }
            assertEquals(if (mode in listOf("success", "wrong-task", "wrong-unit", "wrong-poll-unit", "unchanged")) 1 else 0, accepted.size)
            assertEquals(if (mode in listOf("success", "unchanged", "wrong-poll-unit")) 1 else 0,
                messages.count { it["tool"]?.jsonPrimitive?.content == "task.progress" })
            if (mode in listOf("reject", "wrong-proposal")) assertTrue(messages.any { it["arguments"]?.jsonObject?.get("accepted") == JsonPrimitive(false) })
        } finally { controller.clear(); runtime.close(); scope.cancel(); server.shutdown() }
    }
    @Test fun approvalRefreshesActualPermissionState() = verify("success")
    @Test fun rejectionDoesNotSubmitMutation() = verify("reject")
    @Test fun wrongProposalIsRejected() = verify("wrong-proposal")
    @Test fun wrongTaskCannotReportSuccess() = verify("wrong-task")
    @Test fun invalidApprovalUnitIsStillRejected() = verify("wrong-unit")
    @Test fun invalidPolledUnitIsStillRejected() = verify("wrong-poll-unit")
    @Test fun unchangedObservedPermissionCannotReportSuccess() = verify("unchanged")
    @Test fun disconnectBeforeConfirmationDoesNotSubmit() = verify("disconnect")
}
