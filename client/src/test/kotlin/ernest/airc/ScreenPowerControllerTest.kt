package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ScreenPowerControllerTest {
    @Test fun togglesOnlyAfterSuccessAndRestoresOnReconnect(): Unit = runBlocking {
        val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val server = MockWebServer()
        val commands = CopyOnWriteArrayList<JsonObject>()
        val errors = CopyOnWriteArrayList<String>()
        var on = true
        var fail = false
        fun enqueueSession() {
            server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":["screen.power"]}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(socket: WebSocket, text: String) {
                    val message = Json.parseToJsonElement(text).jsonObject
                    assertEquals("screen.power", message["tool"]!!.jsonPrimitive.content)
                    val args = message["arguments"]!!.jsonObject
                    commands += args
                    if (!fail) args["on"]?.jsonPrimitive?.boolean?.let { on = it }
                    socket.send(buildJsonObject {
                        put("id", message["id"]!!); put("protocol", 1); put("ok", !fail)
                        if (fail) put("error", "platform rejected") else put("result", """{"on":$on}""")
                    }.toString())
                }
            }))
        }
        enqueueSession(); server.start()
        val controller = ScreenPowerController(scope, runtime, errors::add)
        try {
            controller.toggle(); assertTrue(commands.isEmpty())
            runtime.connect(server.hostName, server.port)
            withTimeout(5000) { controller.state.first { it == true } }
            controller.toggle(); controller.toggle()
            withTimeout(5000) { controller.busy.first { !it } }
            assertEquals(false, controller.state.value)
            assertEquals(1, commands.count { it["on"] != null })
            fail = true; controller.toggle()
            withTimeout(5000) { controller.busy.first { !it } }
            assertEquals(false, controller.state.value)
            assertEquals(1, errors.size)
            runtime.disconnect()
            withTimeout(5000) { controller.state.first { it == null } }
            fail = false; enqueueSession()
            runtime.connect(server.hostName, server.port)
            withTimeout(5000) { controller.state.first { it == false } }
            controller.toggle()
            withTimeout(5000) { controller.state.first { it == true } }
        } finally { scope.cancel(); runtime.close(); server.shutdown() }
    }
}
