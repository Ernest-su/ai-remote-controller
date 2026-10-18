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
        val controller = QuickRoutineController(scope) { tool, _ -> calls += tool; "{}" }
        try {
            assertFalse(QuickRoutine("r", "wait", List(7) { RoutineStep("wait", "10000") }).valid())
            assertFalse(RoutineStep("shell", "reboot").valid())
            assertFalse(RoutineStep("app", "a;reboot").valid())
            assertTrue(controller.start(QuickRoutine("r", "play", listOf(RoutineStep("app", "test.player"), RoutineStep("wait", "100"), RoutineStep("key", "ok"))), true))
            withTimeout(3000) { controller.state.first { !it.busy } }
            assertEquals(listOf("apps.open", "remote.key"), calls)
        } finally { scope.cancel() }
    }
}
