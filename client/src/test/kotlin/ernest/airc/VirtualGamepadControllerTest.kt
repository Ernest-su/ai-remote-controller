package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class VirtualGamepadControllerTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val capability = """{"supported":true,"runtimeUid":0,"mode":"uinput"}"""
    private val session = """{"id":"$id","deviceName":"AIRC test","mode":"uinput","registered":true,"gameVerified":false,"deadmanMs":2000}"""
    private fun response(args: JsonObject) = buildJsonObject {
        put("id", id); put("sequence", args["sequence"]!!); put("submitted", true); put("gameVerified", false)
    }.toString()

    @Test fun latestStateIsCoalescedWhileOneUpdateIsInFlightAndStopClosesLease(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val updates = CopyOnWriteArrayList<JsonObject>(); var closed = 0
        val controller = VirtualGamepadController(scope, { tool, args -> when (tool) {
            "gamepad.virtual_capabilities" -> capability
            "gamepad.virtual_open" -> session
            "gamepad.virtual_close" -> { closed++; "{}" }
            else -> { updates += args; if(updates.size == 1) release.await(); response(args) }
        } })
        try {
            assertFalse(controller.start(false)); assertTrue(controller.start(true)); assertFalse(controller.start(true))
            repeat(100) { assertTrue(controller.replace(VirtualGamepadInput(rightX = it / 100.0))) }
            assertEquals(1, updates.size)
            assertFalse(controller.replace(VirtualGamepadInput(buttons = listOf("power"))))
            release.complete(Unit)
            withTimeout(2000) { controller.state.first { it.sequence >= 2 } }
            assertEquals(.99, updates[1]["state"]!!.jsonObject["rightX"]!!.jsonPrimitive.double)
            assertEquals(listOf(1L, 2L), updates.take(2).map { it["sequence"]!!.jsonPrimitive.long })
            controller.stop()
            withTimeout(2000) { controller.state.first { !it.stopping } }
            assertEquals(1, closed); assertEquals(VirtualGamepadInput(), controller.state.value.input)
        } finally { controller.stop(); scope.cancel() }
    }

    @Test fun unchangedStateStillSendsHeartbeatWithoutGrowingAnInputQueue(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val updates = CopyOnWriteArrayList<JsonObject>()
        val controller = VirtualGamepadController(scope, { tool, args -> when(tool) {
            "gamepad.virtual_capabilities" -> capability
            "gamepad.virtual_open" -> session
            "gamepad.virtual_close" -> "{}"
            else -> { updates += args; response(args) }
        } })
        try {
            controller.start(true)
            withTimeout(2000) { controller.state.first { it.sequence >= 2 } }
            assertEquals(updates[0]["state"], updates[1]["state"])
            assertTrue(updates.size <= 3)
        } finally { controller.stop(); scope.cancel() }
    }

    @Test fun stoppingDuringOpenClosesLateLeaseWithoutSubmittingInput(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val opened = CompletableDeferred<String>(); var closed = 0; var updates = 0
        val controller = VirtualGamepadController(scope, { tool, _ -> when(tool) {
            "gamepad.virtual_capabilities" -> capability
            "gamepad.virtual_open" -> opened.await()
            "gamepad.virtual_close" -> { closed++; "{}" }
            else -> { updates++; error("late input") }
        } })
        try {
            controller.start(true); controller.stop()
            assertFalse(controller.start(true)); assertFalse(controller.replace(VirtualGamepadInput(buttons=listOf("a"))))
            opened.complete(session)
            withTimeout(2000) { controller.state.first { !it.stopping } }
            assertEquals(1, closed); assertEquals(0, updates)
        } finally { controller.stop(); scope.cancel() }
    }

    @Test fun changedTargetRejectsCleanupAndNeverUsesTheNewConnection(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val opened = CompletableDeferred<String>(); var target = 1; var afterOpen = 0
        val controller = VirtualGamepadController(scope, { tool, _ -> when(tool) {
            "gamepad.virtual_capabilities" -> capability
            "gamepad.virtual_open" -> opened.await()
            else -> { afterOpen++; error("new target request") }
        } }, { val captured = target; { check(target == captured) { "目标已改变" } } })
        try {
            controller.start(true); target = 2; controller.clear(); opened.complete(session)
            withTimeout(2000) { controller.state.first { !it.stopping } }
            assertEquals(0, afterOpen); assertNull(controller.state.value.error)
        } finally { controller.stop(); scope.cancel() }
    }

    @Test fun unsupportedCapabilityNeverOpensAndInvalidInputsAreRejected(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); var calls = 0
        val controller = VirtualGamepadController(scope, { _, _ -> calls++; """{"supported":false,"reason":"需要 root"}""" })
        try {
            assertFalse(VirtualGamepadInput(leftX=Double.NaN).valid())
            assertFalse(VirtualGamepadInput(hatX=2).valid())
            assertFalse(VirtualGamepadInput(buttons=listOf("a","a")).valid())
            controller.start(true)
            assertEquals(1,calls); assertEquals("需要 root",controller.state.value.error)
            assertFalse(controller.state.value.running)
        } finally { scope.cancel() }
    }
}
