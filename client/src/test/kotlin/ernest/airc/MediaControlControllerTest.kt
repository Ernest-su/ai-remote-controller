package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*
import okhttp3.*
import okhttp3.mockwebserver.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MediaControlControllerTest {
    @Test fun toggleCapabilityEnablesOnlyTheKnownDirection() {
        val player = MediaSession(actions = 512, state = 3)
        assertTrue(player.supportsCommand("pause"))
        assertFalse(player.supportsCommand("play"))
        for (state in listOf(0, 1, 2)) {
            assertTrue(player.copy(state = state).supportsCommand("play"))
            assertFalse(player.copy(state = state).supportsCommand("pause"))
        }
        for (state in listOf(null, 6, 7, 8)) {
            assertFalse(player.copy(state = state).supportsCommand("play"))
            assertFalse(player.copy(state = state).supportsCommand("pause"))
        }
        assertFalse(player.supportsCommand("next"))
        assertTrue(player.copy(actions = 2, state = 6).supportsCommand("pause"))
    }

    @Test fun toggleOnlySessionSendsTheSelectedDirectionalCommand() = runTest {
        var player = session.copy(actions = 512, state = 3)
        val commands = mutableListOf<String>()
        val controller = MediaControlController(backgroundScope) { tool, args ->
            if (tool == "media.command") {
                val command = args.getValue("command").jsonPrimitive.content
                commands += command
                assertEquals("fixture", args.getValue("sessionTag").jsonPrimitive.content)
                player = player.copy(state = if (command == "pause") 2 else 3)
                """{"packageName":"com.test.player","sessionTag":"fixture","command":"$command","submitted":true}"""
            } else Json.encodeToString(MediaSessions(listOf(player), transportAvailable = true))
        }
        controller.refresh(true); runCurrent()
        assertTrue(controller.command("pause", true)); runCurrent()
        assertEquals(2, controller.state.value.sessions.single().state)
        assertTrue(controller.command("play", true)); runCurrent()
        assertEquals(listOf("pause", "play"), commands)
        assertNull(controller.state.value.error)
        controller.clear()
    }
    @Test fun extremeSeekDoesNotTreatUnchangedZeroPositionAsApplied() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val controller=MediaControlController(scope){tool,_->
            if(tool=="media.seek") """{"packageName":"com.test.player","requestedMs":${Long.MAX_VALUE},"submitted":true,"session":${Json.encodeToString(session.copy(positionMs=0,durationMs=null))}}"""
            else Json.encodeToString(MediaSessions(listOf(session.copy(positionMs=0,durationMs=null)),seekAvailable=true))
        }
        try {
            controller.refresh(true)
            assertTrue(controller.seek(Long.MAX_VALUE,true))
            val done=withTimeout(5000){controller.state.first{it.loaded && !it.refreshing && !it.busy}}
            assertNull(done.error)
            assertTrue(done.message!!.contains("等待播放器更新位置"))
        }finally{controller.clear();scope.cancel()}
    }

    private val session = MediaSession("com.test.player", true, 2, 1000, 20000, seekSupported = true, sessionTag = "fixture")
    @Test fun runtimeAdapterUsesBoundSocketInsteadOfUnboundCallback():Unit=runBlocking {
        val runtime=RuntimeConnection();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val server=MockWebServer()
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){
            val message=Json.parseToJsonElement(text).jsonObject
            webSocket.send(buildJsonObject{put("id",message["id"]!!);put("protocol",1);put("ok",true);put("result",Json.encodeToString(MediaSessions(listOf(session),seekAvailable=true)))}.toString())
        }}));server.start()
        var fallback=0
        val controller=MediaControlController(scope,runtime){_,_->fallback++;error("unbound callback")}
        try {
            runtime.connect(server.hostName,server.port);controller.refresh(true)
            val result=withTimeout(3000){controller.state.first{it.loaded && !it.refreshing && !it.busy}}
            assertNull(result.error);assertEquals(listOf(session),result.sessions);assertEquals(0,fallback)
        }finally{controller.clear();scope.cancel();runtime.close();server.shutdown()}
    }
    @Test fun explicitSeekRequiresSupportAndReadsReportedPosition(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var position = 1000L; val calls = mutableListOf<String>()
        val controller = MediaControlController(scope) { tool, args ->
            calls += tool
            if(tool == "media.seek") { position = args["positionMs"]!!.jsonPrimitive.long; """{"packageName":"com.test.player","requestedMs":$position,"submitted":true,"playbackVerified":false,"session":${Json.encodeToString(session.copy(positionMs=position))}}""" }
            else Json.encodeToString(MediaSessions(listOf(session.copy(positionMs = position)), seekAvailable = true))
        }
        try {
            assertFalse(controller.seek(7000, true)); controller.refresh(false); assertTrue(calls.isEmpty())
            controller.refresh(true); assertFalse(controller.seek(-1, true)); assertFalse(controller.seek(21000, true))
            assertTrue(controller.seek(7000, true))
            val done = withTimeout(3000) { controller.state.first { !it.busy } }
            assertNull(done.error); assertTrue(done.message!!.contains("0:07"))
            assertEquals(listOf("media.status", "media.seek"), calls)
        } finally { scope.cancel() }
    }
    @Test fun customActionMustBeDeclaredAndReplyMustMatch(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val action = MediaCustomAction("quality.hd", "HD")
        var called = false
        val controller = MediaControlController(scope) { tool, args ->
            if(tool == "media.custom_action") {
                assertEquals("quality.hd", args["action"]!!.jsonPrimitive.content)
                assertEquals("fixture", args["sessionTag"]!!.jsonPrimitive.content)
                assertNull(args["extras"]); called = true
                """{"packageName":"com.wrong.player","action":"quality.hd","submitted":true}"""
            } else Json.encodeToString(MediaSessions(listOf(session.copy(customActions = listOf(action))), customActionAvailable = true))
        }
        try {
            controller.refresh(true)
            assertFalse(controller.customAction("undeclared", true)); assertFalse(controller.customAction(action.id, false)); assertFalse(called)
            assertTrue(controller.customAction(action.id, true)); assertTrue(called)
            assertTrue(controller.state.value.error!!.contains("对象不匹配"))
        } finally { scope.cancel() }
    }
    @Test fun unsupportedTransportDisablesSeekAndOldRepliesAreDiscarded(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var response: CompletableDeferred<String>? = null
        val controller = MediaControlController(scope) { _, _ -> response?.let { withContext(NonCancellable) { it.await() } } ?: Json.encodeToString(MediaSessions(listOf(session))) }
        try {
            controller.refresh(true); assertFalse(controller.seek(7000, true))
            response = CompletableDeferred(); controller.refresh(true); controller.clear()
            response!!.complete(Json.encodeToString(MediaSessions(listOf(session), seekAvailable = true))); yield()
            assertEquals(MediaControlState(), controller.state.value)
        } finally { scope.cancel() }
    }
    @Test fun backgroundRefreshKeepsControlsAndSelectionStableAndCommandsPreemptStaleReplies() = kotlinx.coroutines.test.runTest {
        val oldReply = CompletableDeferred<String>()
        var polls = 0
        val commands = mutableListOf<String>()
        val player = session.copy(actions = 54, appName = "Fixture music")
        val payload = Json.encodeToString(MediaSessions(listOf(player), seekAvailable = true, transportAvailable = true))
        val controller = MediaControlController(backgroundScope) { tool, args ->
            if (tool == "media.command") {
                commands += args["command"]!!.jsonPrimitive.content
                assertEquals("com.test.player", args["package"]!!.jsonPrimitive.content)
                assertEquals("fixture", args["sessionTag"]!!.jsonPrimitive.content)
                """{"packageName":"com.test.player","sessionTag":"fixture","command":"play","submitted":true}"""
            } else {
                polls++
                if (polls == 2) withContext(NonCancellable) { oldReply.await() } else payload
            }
        }
        controller.refresh(true); runCurrent()
        assertTrue(controller.state.value.loaded)
        controller.refresh(true, quiet = true); runCurrent()
        assertTrue(controller.state.value.refreshing); assertFalse(controller.state.value.busy)
        assertEquals(player.selection(), controller.state.value.selected)
        assertTrue(controller.command("play", true)); runCurrent()
        assertEquals(listOf("play"), commands)
        oldReply.complete(Json.encodeToString(MediaSessions(emptyList())))
        runCurrent()
        assertEquals(listOf(player), controller.state.value.sessions)
        assertFalse(controller.state.value.refreshing); assertFalse(controller.state.value.busy)
        assertFalse(controller.command("delete", true)); assertFalse(controller.command("pause", false))
        controller.clear()
    }
    @Test fun completedPollsWaitThreeSecondsAndObservationCancellationStopsNewRequests() = kotlinx.coroutines.test.runTest {
        val times = mutableListOf<Long>()
        val controller = MediaControlController(backgroundScope) { _, _ ->
            times += testScheduler.currentTime
            delay(1000)
            Json.encodeToString(MediaSessions(listOf(session)))
        }
        val observer = backgroundScope.launch { controller.observe() }
        runCurrent()
        advanceTimeBy(4001); runCurrent()
        assertEquals(listOf(0L, 4000L), times)
        observer.cancelAndJoin()
        advanceTimeBy(12000); runCurrent()
        assertEquals(2, times.size)
        assertFalse(controller.state.value.refreshing)
    }
    @Test fun refreshErrorsPreserveTheLastSnapshotAndCommandAcknowledgementsMustMatchSession() = kotlinx.coroutines.test.runTest {
        var fail = false
        val player = session.copy(actions = 54)
        val controller = MediaControlController(backgroundScope) { tool, _ ->
            if (tool == "media.command") """{"packageName":"com.test.player","sessionTag":"wrong","command":"play","submitted":true}"""
            else if (fail) error("fixture failure")
            else Json.encodeToString(MediaSessions(listOf(player), transportAvailable = true))
        }
        controller.refresh(true); runCurrent()
        assertTrue(controller.command("play", true)); runCurrent()
        assertNotNull(controller.state.value.error)
        fail = true; controller.refresh(true, quiet = true); runCurrent()
        assertEquals(listOf(player), controller.state.value.sessions)
        assertNotNull(controller.state.value.refreshError)
        assertNotNull(controller.state.value.error)
        controller.clear()
    }

    @Test fun aStalledRefreshTimesOutWithoutDiscardingThePreviousPlayer() = runTest {
        var stalled = false
        val controller = MediaControlController(backgroundScope) { _, _ ->
            if (stalled) awaitCancellation()
            Json.encodeToString(MediaSessions(listOf(session)))
        }
        controller.refresh(true); runCurrent()
        stalled = true; controller.refresh(true, quiet = true); runCurrent()
        advanceTimeBy(8001); runCurrent()
        assertFalse(controller.state.value.refreshing)
        assertFalse(controller.state.value.busy)
        assertEquals(listOf(session), controller.state.value.sessions)
        assertTrue(controller.state.value.refreshError!!.contains("超时"))
        controller.clear()
    }

    @Test fun aButtonRenderedForThePreviousSelectionCannotControlTheNewPlayer() = runTest {
        val first = session.copy(actions = 54, customActions = listOf(MediaCustomAction("fixture", "Fixture")))
        val second = first.copy(packageName = "com.second.player")
        val calls = mutableListOf<String>()
        val controller = MediaControlController(backgroundScope) { tool, _ ->
            calls += tool
            Json.encodeToString(MediaSessions(listOf(first, second), seekAvailable = true, customActionAvailable = true, transportAvailable = true))
        }
        controller.refresh(true); runCurrent()
        val rendered = controller.state.value.selected
        controller.choose(checkNotNull(second.selection()))
        assertFalse(controller.command("play", true, rendered))
        assertFalse(controller.seek(7000, true, rendered))
        assertFalse(controller.customAction("fixture", true, rendered))
        assertEquals(listOf("media.status"), calls)
        controller.clear()
    }

    @Test fun unconfirmedSeekReleasesControlsAndTheNextSnapshotReportsRealityWithoutExtraQueries() = runTest {
        var position = 1000L
        val calls = mutableListOf<String>()
        val controller = MediaControlController(backgroundScope) { tool, args ->
            calls += tool
            if (tool == "media.seek") """{"packageName":"com.test.player","requestedMs":${args["positionMs"]},"submitted":true,"session":${Json.encodeToString(session.copy(positionMs=position))}}"""
            else Json.encodeToString(MediaSessions(listOf(session.copy(positionMs=position)), seekAvailable=true))
        }
        controller.refresh(true); runCurrent()
        assertTrue(controller.seek(7000, true)); runCurrent()
        assertFalse(controller.state.value.busy)
        assertEquals(7000L, controller.state.value.pendingSeekMs)
        assertEquals(listOf("media.status", "media.seek"), calls)
        // A second drag is accepted immediately; an old target cannot keep controls locked.
        assertTrue(controller.seek(9000, true)); runCurrent()
        assertEquals(9000L, controller.state.value.pendingSeekMs)
        position = 9000; controller.refresh(true, quiet=true); runCurrent()
        assertNull(controller.state.value.pendingSeekMs)
        assertTrue(controller.state.value.message!!.contains("0:09"))
        assertEquals(9000L, controller.state.value.sessions.single().positionMs)
        controller.clear()
    }
    @Test fun seekAcknowledgementFromAnotherSessionCannotReplaceTheSelectedPlayer() = runTest {
        val controller = MediaControlController(backgroundScope) { tool, _ ->
            if (tool == "media.seek") """{"packageName":"com.test.player","requestedMs":7000,"submitted":true,"session":${Json.encodeToString(session.copy(sessionTag="wrong",positionMs=7000))}}"""
            else Json.encodeToString(MediaSessions(listOf(session), seekAvailable=true))
        }
        controller.refresh(true); runCurrent()
        assertTrue(controller.seek(7000,true)); runCurrent()
        assertNotNull(controller.state.value.error)
        assertEquals(listOf(session),controller.state.value.sessions)
        assertFalse(controller.state.value.busy)
        controller.clear()
    }

}
