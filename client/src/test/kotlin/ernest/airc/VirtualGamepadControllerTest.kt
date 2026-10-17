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

    @Test fun typeSwitchWaitsForNeutralAckAndRejectsOldLayoutInput(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val heldEntered = CompletableDeferred<Unit>()
        val heldReply = CompletableDeferred<Unit>()
        val neutralEntered = CompletableDeferred<Unit>()
        val neutralReply = CompletableDeferred<Unit>()
        var held = false
        val updates = CopyOnWriteArrayList<JsonObject>()
        val controller = VirtualGamepadController(scope, { tool, args -> when (tool) {
            "gamepad.virtual_capabilities" -> capability
            "gamepad.virtual_open" -> session
            "gamepad.virtual_close" -> "{}"
            else -> {
                val input = args["state"]!!.jsonObject
                updates += input
                if (input["leftX"]!!.jsonPrimitive.double > 0) {
                    held = true; heldEntered.complete(Unit); heldReply.await()
                } else if (held) { neutralEntered.complete(Unit); neutralReply.await() }
                response(args)
            }
        } })
        try {
            controller.start(true)
            assertTrue(controller.replace(VirtualGamepadInput(leftX = 1.0, buttons = listOf("a"))))
            withTimeout(2000) { heldEntered.await() }
            controller.selectType(GamepadType.NES)
            assertTrue(controller.state.value.switchingType)
            assertEquals(VirtualGamepadInput(), controller.state.value.input)
            assertFalse(controller.replace(VirtualGamepadInput(hatX = 1)))
            heldReply.complete(Unit)
            withTimeout(2000) { neutralEntered.await() }
            assertTrue(controller.state.value.switchingType, "Old in-flight reply cannot enable new controls")
            assertEquals(0.0, updates.last()["leftX"]!!.jsonPrimitive.double)
            assertTrue(updates.last()["buttons"]!!.jsonArray.isEmpty())
            neutralReply.complete(Unit)
            withTimeout(2000) { controller.state.first { !it.switchingType } }
            assertFalse(controller.change(GamepadType.DUAL_STICK) { it.copy(leftX = 1.0) })
            assertTrue(controller.change(GamepadType.NES) { it.copy(hatX = 1, buttons = listOf("a")) })
            withTimeout(2000) { while (updates.last()["hatX"]!!.jsonPrimitive.int != 1) delay(10) }
            controller.stop()
            withTimeout(2000) { controller.state.first { !it.stopping } }
            assertEquals(GamepadType.NES, controller.state.value.type)
            controller.start(true)
            assertEquals(GamepadType.NES, controller.state.value.type)
            assertEquals(VirtualGamepadInput(), controller.state.value.input)
        } finally { heldReply.complete(Unit); neutralReply.complete(Unit); controller.stop(); scope.cancel() }
    }

    @Test fun nesSyntheticMapsDiagonalAndJumpToKeysAndSwitchesThroughReset(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val controller = VirtualGamepadController(scope, { tool, args ->
            if (tool == "gamepad.virtual_capabilities") """{"supported":false,"runtimeUid":2000,"mode":"uinput"}"""
            else {
                calls += tool to args
                buildJsonObject {
                    put("submitted", true); put("released", true); put("sourceGamepad", 1025)
                    put("sourceJoystick", 16777232); put("operation", tool.substringAfter('.')); put("gameVerified", false)
                }.toString()
            }
        })
        try {
            controller.selectType(GamepadType.NES)
            controller.start(true, allowSynthetic = true)
            assertTrue(controller.replace(VirtualGamepadInput(hatX = 1, hatY = -1, buttons = listOf("a"))))
            withTimeout(2000) { while (calls.none { it.first == "gamepad.pulse" }) delay(10) }
            val pulse = calls.first { it.first == "gamepad.pulse" }.second
            assertEquals(listOf("a", "right", "up"), pulse["buttons"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(0.0, pulse["x"]!!.jsonPrimitive.double)
            controller.selectType(GamepadType.DUAL_STICK)
            assertFalse(controller.replace(VirtualGamepadInput(leftX = 1.0)))
            withTimeout(2000) { controller.state.first { !it.switchingType } }
            assertEquals("gamepad.reset", calls.last().first)
            assertTrue(controller.replace(VirtualGamepadInput(leftX = 1.0)))
            withTimeout(2000) { while (calls.last().second["x"]?.jsonPrimitive?.double != 1.0) delay(10) }
            assertTrue(calls.last().second["buttons"]!!.jsonArray.isEmpty())
        } finally { controller.stop(); scope.cancel() }
    }

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

    @Test fun updateTimeoutRecoversPromptlyWithNeutralInputEvenWhenCloseDoesNotReply(): Unit = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val stalled=CompletableDeferred<Unit>()
        val snapshots=CopyOnWriteArrayList<JsonObject>()
        val opens=java.util.concurrent.atomic.AtomicInteger()
        val controller=VirtualGamepadController(scope,{tool,args -> when(tool) {
            "gamepad.virtual_capabilities" -> capability
            "gamepad.virtual_open" -> { opens.incrementAndGet(); session }
            "gamepad.virtual_close" -> { if(opens.get()==1) awaitCancellation(); "{}" }
            else -> {
                snapshots+=args
                if(opens.get()==1 && (stalled.isCompleted || args["state"]!!.jsonObject["buttons"]!!.jsonArray.isNotEmpty())) {
                    stalled.complete(Unit); awaitCancellation()
                }
                response(args)
            }
        }})
        try {
            controller.start(true)
            withTimeout(2000){controller.state.first{it.sequence>0}}
            controller.replace(VirtualGamepadInput(buttons=listOf("a"),rightX=.8))
            stalled.await()
            val began=System.nanoTime()
            withTimeout(2500){controller.state.first{it.reconnecting}}
            assertEquals(VirtualGamepadInput(),controller.state.value.input)
            assertFalse(controller.replace(VirtualGamepadInput(buttons=listOf("b"))))
            withTimeout(3500){controller.state.first{!it.reconnecting && it.sequence>0 && opens.get()==2}}
            assertTrue((System.nanoTime()-began)/1_000_000 < 3300,"Recovery must not wait five seconds for cleanup")
            assertEquals(Json { encodeDefaults = true }.encodeToJsonElement(VirtualGamepadInput.serializer(),VirtualGamepadInput()),snapshots.last()["state"])
            assertNull(controller.state.value.error)
        } finally {controller.stop();scope.cancel()}
    }

    @Test fun lostOpenAcknowledgementAutomaticallyReopensOnTheSameConnection(): Unit = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val opens=java.util.concurrent.atomic.AtomicInteger()
        val controller=VirtualGamepadController(scope,{tool,args -> when(tool) {
            "gamepad.virtual_capabilities" -> capability
            "gamepad.virtual_open" -> { if(opens.incrementAndGet()==1) awaitCancellation(); session }
            "gamepad.virtual_close" -> "{}"
            else -> response(args)
        }})
        try {
            controller.start(true)
            withTimeout(3000){controller.state.first{opens.get()==2 && it.sequence>0}}
            assertTrue(controller.state.value.running)
            assertFalse(controller.state.value.reconnecting)
            assertEquals(VirtualGamepadInput(),controller.state.value.input)
        } finally {controller.stop();scope.cancel()}
    }

    @Test fun stopDuringRecoveryPreventsAnyFurtherOpen(): Unit = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val cleanup=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val opens=java.util.concurrent.atomic.AtomicInteger()
        val controller=VirtualGamepadController(scope,{tool,_ -> when(tool) {
            "gamepad.virtual_capabilities" -> capability
            "gamepad.virtual_open" -> {opens.incrementAndGet();session}
            "gamepad.virtual_close" -> {cleanup.complete(Unit);release.await();"{}"}
            else -> error("session expired")
        }})
        try {
            controller.start(true);cleanup.await()
            assertTrue(controller.state.value.reconnecting)
            controller.stop();release.complete(Unit)
            withTimeout(2000){controller.state.first{!it.stopping}}
            delay(300)
            assertEquals(1,opens.get());assertFalse(controller.state.value.running)
            assertFalse(controller.state.value.reconnecting)
        } finally {release.complete(Unit);controller.stop();scope.cancel()}
    }

    @Test fun recoveryNeverCapturesAChangedTargetAndRetryCountIsBounded(): Unit = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val opens=java.util.concurrent.atomic.AtomicInteger()
        val controller=VirtualGamepadController(scope,{tool,args -> when(tool) {
            "gamepad.virtual_capabilities" -> capability
            "gamepad.virtual_open" -> {opens.incrementAndGet();session}
            "gamepad.virtual_close" -> "{}"
            else -> if(args["sequence"]!!.jsonPrimitive.long==1L) response(args) else error("session expired")
        }})
        try {
            controller.start(true)
            withTimeout(5000){controller.state.first{!it.running}}
            assertEquals(4,opens.get())
            assertTrue(controller.state.value.error!!.contains("自动重连失败"))
        } finally {controller.stop();scope.cancel()}
        val otherScope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var target=1;var requests=0;var captures=0
        val guarded=VirtualGamepadController(otherScope,{tool,_ -> requests++;when(tool) {
            "gamepad.virtual_capabilities" -> capability
            "gamepad.virtual_open" -> session
            else -> {target=2;error("connection changed")}
        }},{captures++;val original=target;{check(target==original){"目标已改变"}}})
        try {
            guarded.start(true)
            assertFalse(guarded.state.value.running)
            assertEquals(1,captures);assertEquals(3,requests)
            assertEquals("目标已改变",guarded.state.value.error)
        } finally {guarded.stop();otherScope.cancel()}
    }

    @Test fun malformedUpdateReplyIsFatalRatherThanRetried(): Unit = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var opens=0
        val controller=VirtualGamepadController(scope,{tool,_ -> when(tool) {
            "gamepad.virtual_capabilities" -> capability
            "gamepad.virtual_open" -> {opens++;session}
            else -> "{}"
        }})
        try {
            controller.start(true)
            assertFalse(controller.state.value.running)
            assertEquals(1,opens)
            assertEquals("虚拟手柄更新响应不匹配",controller.state.value.error)
        } finally {controller.stop();scope.cancel()}
    }

    @Test fun syntheticRequestFailureRecoversWithoutReplayingHeldInput(): Unit = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val capabilities=java.util.concurrent.atomic.AtomicInteger()
        val pulses=java.util.concurrent.atomic.AtomicInteger()
        val resets=java.util.concurrent.atomic.AtomicInteger()
        val controller=VirtualGamepadController(scope,{tool,_ -> when(tool) {
            "gamepad.virtual_capabilities" -> {capabilities.incrementAndGet();"""{"supported":false,"runtimeUid":2000,"mode":"uinput"}"""}
            "gamepad.pulse" -> {pulses.incrementAndGet();error("temporary input request failure")}
            "gamepad.reset" -> {resets.incrementAndGet();"{}"}
            else -> error(tool)
        }})
        try {
            controller.start(true,allowSynthetic=true)
            withTimeout(2000){controller.state.first{it.mode=="synthetic"}}
            controller.replace(VirtualGamepadInput(buttons=listOf("a")))
            withTimeout(2000){controller.state.first{capabilities.get()==2 && !it.reconnecting}}
            delay(200)
            assertEquals(1,pulses.get());assertEquals(1,resets.get())
            assertEquals(VirtualGamepadInput(),controller.state.value.input)
            assertTrue(controller.state.value.running);assertNull(controller.state.value.error)
        } finally {controller.stop();scope.cancel()}
    }

}
