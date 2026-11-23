package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MouseControllerTest {
    private class Endpoint {
        val calls = mutableListOf<Pair<String,JsonObject>>()
        var failUpdate = false
        var blockUpdate: CompletableDeferred<Unit>? = null
        var blockClose: CompletableDeferred<Unit>? = null
        suspend fun send(tool: String, args: JsonObject): String {
            calls += tool to args
            return when (tool) {
                "mouse.capabilities" -> """{"supported":true}"""
                "mouse.open" -> """{"id":"owned"}"""
                "mouse.update" -> {
                    blockUpdate?.await()
                    check(!failUpdate) { "lost reply" }
                    buildJsonObject { put("id","owned"); put("sequence", args.getValue("sequence")); put("submitted",true) }.toString()
                }
                "mouse.close" -> { blockClose?.await(); """{"id":"owned","closed":true}""" }
                else -> error(tool)
            }
        }
    }
    @Test fun coalescesMotionButPreservesQuickClicksAndUsesOriginalWriterForClose() = runTest {
        val target = Endpoint(); val replacement = Endpoint()
        var current = target; var writers = 0
        val mouse = MouseController(this) { writers++; val captured = current; { tool,args -> captured.send(tool,args) } }
        assertTrue(mouse.start(true)); advanceTimeBy(151); runCurrent()
        assertTrue(mouse.state.value.running)
        repeat(10_000) { mouse.move(0.01f,0f) }
        mouse.button(1,true); mouse.move(10f,0f); mouse.button(1,false)
        mouse.button(4,true); mouse.button(4,false); mouse.scroll(-2)
        advanceTimeBy(300); runCurrent()
        val frames = target.calls.filter { it.first == "mouse.update" }.map { it.second.getValue("frame").jsonObject }
        assertEquals(listOf(0,1,0,4,0),frames.take(5).map { it.getValue("buttons").jsonPrimitive.int })
        assertEquals(110, frames.sumOf { it.getValue("dx").jsonPrimitive.int })
        assertEquals(-2,frames.sumOf { it.getValue("wheel").jsonPrimitive.int })
        assertTrue(frames.size < 10)
        current = replacement; mouse.stop(); runCurrent()
        assertEquals(1,writers); assertTrue(replacement.calls.isEmpty())
        assertEquals("mouse.close",target.calls.last().first)
        assertFalse(mouse.state.value.running)
    }
    @Test fun leftAndRightRemainHeldAcrossMotionAndHeartbeatsUntilTheirOwnRelease() = runTest {
        val target=Endpoint(); val mouse=MouseController(this) { target::send }
        mouse.start(true); advanceTimeBy(151); runCurrent()
        mouse.button(1,true); runCurrent(); advanceTimeBy(300); runCurrent()
        fun masks()=target.calls.filter { it.first=="mouse.update" }.map { it.second.getValue("frame").jsonObject.getValue("buttons").jsonPrimitive.int }
        assertTrue(masks().all { it==1 })
        mouse.button(2,true); mouse.move(15f,10f)
        advanceTimeBy(600); runCurrent()
        assertEquals(3,masks().last())
        assertEquals(1,masks().zipWithNext().count { (before,after) -> before==1 && after==3 })
        mouse.button(1,false); advanceTimeBy(350); runCurrent()
        assertEquals(2,masks().last())
        mouse.button(2,false); advanceTimeBy(350); runCurrent()
        assertEquals(0,masks().last())
        assertEquals(listOf(1,3,2,0),masks().fold(mutableListOf<Int>()) { values,mask -> values.apply { if(lastOrNull()!=mask) add(mask) } })
        mouse.stop(); runCurrent()
    }
    @Test fun failedUpdateStopsWithoutReplayingMovementAndCloses() = runTest {
        val target=Endpoint(); val mouse=MouseController(this) { target::send }
        mouse.start(true); advanceTimeBy(151); runCurrent()
        target.failUpdate=true; mouse.move(12f,0f); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(1,target.calls.count { it.first=="mouse.update" })
        assertEquals("mouse.close",target.calls.last().first)
        assertFalse(mouse.state.value.running); assertNotNull(mouse.state.value.error)
    }
    @Test fun slowConnectionDoesNotBuildAnUnboundedClickQueue() = runTest {
        val target=Endpoint(); val mouse=MouseController(this) { target::send }
        mouse.start(true); advanceTimeBy(151); runCurrent()
        target.blockUpdate=CompletableDeferred(); mouse.button(1,true); runCurrent()
        repeat(100) { mouse.button(1,it%2==1) }
        runCurrent()
        assertFalse(mouse.state.value.running); assertNotNull(mouse.state.value.error)
        assertEquals("mouse.close",target.calls.last().first)
    }
    @Test fun automaticActivationWaitsForOldSessionRelease() = runTest {
        val target=Endpoint(); val mouse=MouseController(this) { target::send }
        mouse.start(true); advanceTimeBy(151); runCurrent()
        val close=CompletableDeferred<Unit>(); target.blockClose=close
        val next=async { mouse.activate(true) }
        runCurrent()
        assertEquals(1,target.calls.count { it.first == "mouse.open" })
        assertFalse(next.isCompleted)
        close.complete(Unit); runCurrent(); advanceTimeBy(151); runCurrent()
        assertTrue(next.await()); assertTrue(mouse.state.value.running)
        val operations=target.calls.map { it.first }
        assertTrue(operations.indexOf("mouse.close") < operations.indexOfLast { it == "mouse.open" })
        mouse.stop(); runCurrent()
    }
    @Test fun canceledAutomaticActivationCannotOpenAfterLeavingPage() = runTest {
        val target=Endpoint(); val mouse=MouseController(this) { target::send }
        mouse.start(true); advanceTimeBy(151); runCurrent()
        val close=CompletableDeferred<Unit>(); target.blockClose=close
        val next=launch { mouse.activate(true) }; runCurrent()
        next.cancel(); close.complete(Unit); runCurrent()
        assertEquals(1,target.calls.count { it.first == "mouse.open" })
        assertFalse(mouse.state.value.running)
    }
    @Test fun unavailableDoesNotCaptureWriterOrOpenDevice() = runTest {
        val mouse=MouseController(this) { error("must not create writer") }
        assertFalse(mouse.start(false)); assertFalse(mouse.state.value.running)
    }
}
