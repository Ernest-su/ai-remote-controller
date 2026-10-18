package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class QuickRoutineControllerTest {
    @Test fun stopsAfterFailureAndRejectsUnsupportedSteps() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<String>()
        val controller = QuickRoutineController(scope) { tool, _ -> calls += tool; error("fixture failure") }
        try {
            assertFalse(controller.start(QuickRoutine("r", "unsafe", listOf(RoutineStep("key", "power"))), true))
            val routine = QuickRoutine("r", "launch", listOf(RoutineStep("app", "test.player"), RoutineStep("key", "ok")))
            assertFalse(controller.start(routine, false))
            assertTrue(controller.start(routine, true))
            val done = withTimeout(3000) { controller.state.first { !it.busy } }
            assertEquals(listOf("apps.open"), calls)
            assertEquals("fixture failure", done.error)
        } finally { scope.cancel() }
    }
    @Test fun cancellationDuringWaitNeverSubmitsNextStep() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val controller = QuickRoutineController(scope) { _, _ -> calls++; "{}" }
        try {
            val routine = QuickRoutine("r", "wait", listOf(RoutineStep("wait", "10000"), RoutineStep("key", "ok")))
            assertTrue(controller.start(routine, true))
            assertFalse(controller.start(routine, true))
            controller.cancel()
            withTimeout(3000) { controller.state.first { !it.busy } }
            assertEquals(0, calls)
        } finally { scope.cancel() }
    }
    @Test fun reconnectDuringWaitCannotSendToTheNewConnection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = RuntimeConnection()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                if (request.path == "/status") okhttp3.mockwebserver.MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"routine-fixture","tools":[]}""")
                else okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {})
        }
        server.start()
        val controller = QuickRoutineController(scope, runtime)
        try {
            runtime.connect(server.hostName, server.port)
            controller.start(QuickRoutine("r", "wait", listOf(RoutineStep("wait", "300"), RoutineStep("key", "ok"))), true)
            withTimeout(3000) { controller.state.first { it.index == 1 } }
            runtime.disconnect()
            runtime.connect(server.hostName, server.port)
            val done = withTimeout(3000) { controller.state.first { !it.busy } }
            assertNotNull(done.error)
            assertEquals(1, done.index)
        } finally { controller.cancel(); scope.cancel(); runtime.close(); server.shutdown() }
    }
    @Test fun sequenceKeepsStepOrderAndRejectsLongWaits() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<String>()
        val controller = QuickRoutineController(scope) { tool, _ -> calls += tool; if (tool == "remote.key") """{"operation":"key","submitted":true,"released":true}""" else "{}" }
        try {
            assertFalse(QuickRoutine("r", "wait", List(7) { RoutineStep("wait", "10000") }).valid())
            assertFalse(RoutineStep("shell", "reboot").valid())
            assertFalse(RoutineStep("app", "a;reboot").valid())
            assertTrue(controller.start(QuickRoutine("r", "play", listOf(RoutineStep("app", "test.player"), RoutineStep("wait", "100"), RoutineStep("key", "ok"))), true))
            withTimeout(3000) { controller.state.first { !it.busy } }
            assertEquals(listOf("apps.open", "remote.key"), calls)
        } finally { scope.cancel() }
    }
    @Test fun pausePreservesRemainingWaitAndResumeDoesNotReplayCompletedSteps() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<String>()
        val controller = QuickRoutineController(scope) { tool, _ -> calls += tool; "{}" }
        try {
            controller.start(QuickRoutine("r", "pause", listOf(RoutineStep("app", "test.player"), RoutineStep("wait", "300"), RoutineStep("app", "test.settings"))), true)
            withTimeout(3000) { controller.state.first { it.index == 2 } }
            controller.pause()
            withTimeout(3000) { controller.state.first { it.paused } }
            delay(400)
            assertEquals(listOf("apps.open"), calls)
            assertEquals(RoutineStatus.PAUSED, controller.history.value.single().status)
            controller.resume()
            delay(100)
            assertEquals(listOf("apps.open"), calls)
            withTimeout(3000) { controller.state.first { !it.busy } }
            assertEquals(listOf("apps.open", "apps.open"), calls)
            assertEquals(RoutineStatus.SUCCEEDED, controller.history.value.single().status)
            assertTrue(controller.history.value.single().steps.all { it.status == RoutineStepStatus.SUCCEEDED })
        } finally { scope.cancel() }
    }
    @Test fun pauseDuringRequestWaitsForReceiptWithoutReplayingIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val receipt = CompletableDeferred<String>()
        var calls = 0
        val controller = QuickRoutineController(scope) { _, _ -> calls++; if (calls == 1) receipt.await() else "{}" }
        try {
            controller.start(QuickRoutine("r", "request", List(2) { RoutineStep("app", "test.player") }), true)
            assertEquals(1, calls)
            controller.pause()
            assertTrue(controller.state.value.pauseRequested)
            assertFalse(controller.state.value.paused)
            receipt.complete("{}")
            withTimeout(3000) { controller.state.first { it.paused } }
            assertEquals(1, calls)
            controller.cancel()
            withTimeout(3000) { controller.state.first { !it.busy } }
            assertEquals(RoutineStatus.STOPPED, controller.history.value.single().status)
            assertEquals(RoutineStepStatus.SUCCEEDED, controller.history.value.single().steps[0].status)
            assertEquals(RoutineStepStatus.PENDING, controller.history.value.single().steps[1].status)
        } finally { scope.cancel() }
    }
    @Test fun missingKeyReleaseFailsOnceAndRetainsPerDeviceDetails() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        var saved = emptyList<RoutineExecution>()
        val controller = QuickRoutineController(scope, onHistoryChanged = { saved = it }) { _, _ -> calls++; """{"operation":"key","submitted":true,"released":false}""" }
        val routine = QuickRoutine("r", "keys", List(2) { RoutineStep("key", "ok") })
        try {
            controller.start(routine, true, "tv-one", "客厅")
            withTimeout(3000) { controller.state.first { !it.busy } }
            assertEquals(1, calls)
            assertEquals(RoutineStatus.FAILED, saved.single().status)
            assertEquals(RoutineStepStatus.FAILED, saved.single().steps[0].status)
            assertEquals(RoutineStepStatus.PENDING, saved.single().steps[1].status)
            assertNotNull(saved.single().error)
            assertTrue(saved.single().steps[0].output.orEmpty().contains("false"))
            controller.start(routine, true, "tv-two", "卧室")
            withTimeout(3000) { controller.state.first { !it.busy } }
            assertEquals(setOf("tv-one", "tv-two"), saved.map { it.deviceId }.toSet())
            val restored = QuickRoutineController(scope, initialHistory = saved) { _, _ -> "{}" }
            assertEquals(saved, restored.history.value)
            controller.forget("tv-one", "r")
            assertEquals("tv-two", controller.history.value.single().deviceId)
        } finally { scope.cancel() }
    }
    @Test fun restartedAppMarksInFlightStepUncertainAndNeverResumesIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val routine = QuickRoutine("r", "restart", listOf(RoutineStep("key", "ok")))
        try {
            val controller = QuickRoutineController(scope, initialHistory = listOf(RoutineExecution(routine, startedAt = 1,
                steps = listOf(RoutineStepExecution(RoutineStepStatus.RUNNING, startedAt = 1))))) { _, _ -> calls++; "{}" }
            assertEquals(RoutineStatus.STOPPED, controller.history.value.single().status)
            assertEquals(RoutineStepStatus.INTERRUPTED, controller.history.value.single().steps.single().status)
            assertEquals(0, calls)
            assertFalse(controller.state.value.busy)
        } finally { scope.cancel() }
    }

}
