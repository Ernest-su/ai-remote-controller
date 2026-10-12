package ernest.airc
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import okhttp3.*
import okhttp3.mockwebserver.*
import kotlinx.coroutines.flow.first

class GamepadControllerTest {
    @Test fun runtimePulseUsesBoundConnectionInsteadOfFallbackCallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = RuntimeConnection()
        val server = MockWebServer()
        var fallback = 0
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = Json.parseToJsonElement(text).jsonObject
                assertEquals("gamepad.pulse", request["tool"]!!.jsonPrimitive.content)
                webSocket.send(buildJsonObject {
                    put("id", request["id"]!!); put("protocol", 1); put("ok", true); put("result", response)
                }.toString())
            }
        }))
        server.start()
        val controller = GamepadController(scope, runtime) { _, _ -> fallback++; error("unbound callback") }
        try {
            runtime.connect(server.hostName, server.port)
            assertTrue(controller.pulse(GamepadPulse(listOf("a")), true))
            val result = withTimeout(3000) { controller.state.first { !it.busy } }
            assertNull(result.error)
            assertNotNull(result.message)
            assertEquals(0, fallback)
        } finally { controller.clear(); scope.cancel(); runtime.close(); server.shutdown() }
    }

    private val response="""{"submitted":true,"released":true,"gameVerified":false,"sourceGamepad":1025,"sourceJoystick":16777232,"operation":"pulse"}"""
    @Test fun pulseUsesTypedInputsRejectsUnsupportedActionsAndChecksRelease(): Unit = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var calls=0;var release=true
        val controller=GamepadController(scope){tool,args->
            calls++;assertEquals("gamepad.pulse",tool);assertEquals(300,args["holdMs"]!!.jsonPrimitive.int);assertEquals(-0.5,args["y"]!!.jsonPrimitive.double)
            if(release)response else response.replace("\"released\":true","\"released\":false")
        }
        try {
            assertFalse(controller.pulse(GamepadPulse(listOf("power")),true));assertFalse(controller.pulse(GamepadPulse(listOf("a")),false));assertFalse(controller.pulse(GamepadPulse(x=Double.NaN),true));assertEquals(0,calls)
            assertTrue(controller.pulse(GamepadPulse(listOf("a","r1"),y=-0.5,holdMs=300),true));assertNull(controller.state.value.error)
            release=false;assertTrue(controller.pulse(GamepadPulse(listOf("a"),y=-0.5,holdMs=300),true));assertTrue(controller.state.value.error!!.contains("释放未确认"))
        } finally {scope.cancel()}
    }
    @Test fun disconnectClearDiscardsLateResultAndDoesNotPretendToCancelTargetInput(): Unit = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val reply=CompletableDeferred<String>()
        val controller=GamepadController(scope){_,_->withContext(NonCancellable){reply.await()}}
        try {
            assertTrue(controller.pulse(GamepadPulse(listOf("a")),true));assertFalse(controller.reset(true));controller.clear();reply.complete(response);yield()
            assertEquals(GamepadControlState(),controller.state.value)
        } finally {scope.cancel()}
    }
}
