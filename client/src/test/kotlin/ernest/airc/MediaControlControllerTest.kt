package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*
import okhttp3.*
import okhttp3.mockwebserver.*

class MediaControlControllerTest {
    @Test fun extremeSeekDoesNotTreatUnchangedZeroPositionAsApplied() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val controller=MediaControlController(scope){tool,_->
            if(tool=="media.seek") """{"packageName":"com.test.player","requestedMs":${Long.MAX_VALUE},"submitted":true}"""
            else Json.encodeToString(MediaSessions(listOf(session.copy(positionMs=0,durationMs=null)),seekAvailable=true))
        }
        try {
            controller.refresh(true)
            assertTrue(controller.seek(Long.MAX_VALUE,true))
            val done=withTimeout(5000){controller.state.first{!it.busy}}
            assertNull(done.error)
            assertTrue(done.message!!.contains("尚未报告目标位置"))
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
            val result=withTimeout(3000){controller.state.first{!it.busy}}
            assertNull(result.error);assertEquals(listOf(session),result.sessions);assertEquals(0,fallback)
        }finally{controller.clear();scope.cancel();runtime.close();server.shutdown()}
    }
    @Test fun explicitSeekRequiresSupportAndReadsReportedPosition(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var position = 1000L; val calls = mutableListOf<String>()
        val controller = MediaControlController(scope) { tool, args ->
            calls += tool
            if(tool == "media.seek") { position = args["positionMs"]!!.jsonPrimitive.long; """{"packageName":"com.test.player","requestedMs":$position,"submitted":true,"playbackVerified":false}""" }
            else Json.encodeToString(MediaSessions(listOf(session.copy(positionMs = position)), seekAvailable = true))
        }
        try {
            assertFalse(controller.seek(7000, true)); controller.refresh(false); assertTrue(calls.isEmpty())
            controller.refresh(true); assertFalse(controller.seek(-1, true)); assertFalse(controller.seek(21000, true))
            assertTrue(controller.seek(7000, true))
            val done = withTimeout(3000) { controller.state.first { !it.busy } }
            assertNull(done.error); assertTrue(done.message!!.contains("7000 毫秒"))
            assertEquals(listOf("media.status", "media.seek", "media.status"), calls)
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
}
