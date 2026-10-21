package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class RemoteVolumeControllerTest {
    @Test fun pollsWritesRoundedTargetIndexAndClearsOnDisconnect() = verify(Dispatchers.Default)
    @Test fun immediateSessionCollectorWaitsForConnectedPublication() = verify(Dispatchers.Unconfined)

    private fun verify(dispatcher: CoroutineDispatcher): Unit = runBlocking {
        val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val commands = CopyOnWriteArrayList<JsonObject>()
        val errors = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":["media.volume"]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                val message = Json.parseToJsonElement(text).jsonObject
                assertEquals("media.volume", message["tool"]!!.jsonPrimitive.content)
                val command = message["arguments"]!!.jsonObject
                commands += command
                val index = if (command["percent"]?.jsonPrimitive?.int == 50) 9 else 5
                socket.send(buildJsonObject {
                    put("id", message["id"]!!); put("protocol", 1); put("ok", true)
                    put("result", """{"volumeIndex":$index,"minVolumeIndex":3,"maxVolumeIndex":15,"fixed":false}""")
                }.toString())
            }
        }))
        server.start()
        val controller = RemoteVolumeController(scope, runtime, errors::add)
        try {
            controller.setFraction(1f)
            assertTrue(commands.isEmpty())
            runtime.connect(server.hostName, server.port)
            withTimeout(5000) { controller.state.first { it?.index == 5 } }
            controller.setFraction(0.5f)
            withTimeout(5000) { controller.state.first { it?.index == 9 } }
            assertTrue(commands.any { it["percent"]?.jsonPrimitive?.int == 50 })
            assertTrue(errors.isEmpty())
            runtime.disconnect()
            withTimeout(5000) { controller.state.first { it == null } }
        } finally { scope.cancel(); runtime.close(); server.shutdown() }
    }
}
