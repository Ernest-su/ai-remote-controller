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
    @Test fun nonRootUsesBothSticksAndAllSixButtonsThenResetsOnStop(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pulses = CopyOnWriteArrayList<JsonObject>()
        val release = CompletableDeferred<Unit>()
        var resets = 0
        val controller = VirtualGamepadController(scope, { tool, args -> when (tool) {
            "gamepad.virtual_capabilities" -> """{"supported":false,"runtimeUid":2000,"mode":"uinput","reason":"需要 root"}"""
            "gamepad.pulse" -> {
                pulses += args
                release.await()
                """{"operation":"pulse","submitted":true,"released":true,"sourceGamepad":1025,"sourceJoystick":16777232,"gameVerified":false}"""
            }
            "gamepad.reset" -> { resets++; "{}" }
            else -> error("Non-root must not open a uinput device: $tool")
        } })
        try {
            assertTrue(controller.start(true, allowSynthetic = true))
            assertEquals("synthetic", controller.state.value.mode)
            assertTrue(controller.replace(VirtualGamepadInput(
                buttons = listOf("a", "b", "x", "y", "select", "start"), leftX = .5, rightX = -.75, rightY = 1.0)))
            withTimeout(2000) { while (pulses.isEmpty()) delay(10) }
            assertEquals(.5, pulses[0]["x"]!!.jsonPrimitive.double)
            assertEquals(-.75, pulses[0]["rightX"]!!.jsonPrimitive.double)
            assertEquals(1.0, pulses[0]["rightY"]!!.jsonPrimitive.double)
            assertEquals(6, pulses[0]["buttons"]!!.jsonArray.size)
            controller.stop()
            assertTrue(controller.state.value.stopping)
            assertEquals(0, resets, "Release must wait for the in-flight finite transaction")
            release.complete(Unit)
            withTimeout(2000) { controller.state.first { !it.stopping } }
            assertEquals(1, resets)
            assertEquals(VirtualGamepadInput(), controller.state.value.input)
            assertEquals(1, pulses.size)
        } finally { release.complete(Unit); controller.stop(); scope.cancel() }
    }

    @Test fun syntheticReleaseFailureStopsInputAndReportsError(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var resets = 0
        val controller = VirtualGamepadController(scope, { tool, _ -> when (tool) {
            "gamepad.virtual_capabilities" -> """{"supported":false,"runtimeUid":2000,"mode":"uinput"}"""
            "gamepad.pulse" -> """{"submitted":true,"released":false}"""
            "gamepad.reset" -> { resets++; "{}" }
            else -> error(tool)
        } })
        try {
            controller.start(true, allowSynthetic = true)
            controller.replace(VirtualGamepadInput(rightY = .5))
            withTimeout(2000) { controller.state.first { !it.running } }
            assertTrue(controller.state.value.error!!.contains("释放未确认"))
            assertEquals(1, resets)
        } finally { controller.stop(); scope.cancel() }
    }

}
