package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Exercises dropped and late replies through the production WebSocket request map. */
class VirtualGamepadRecoveryTransportTest {
    @Test fun oneLostReplyProbesTheSameLeaseAndLateAckCannotRestoreOldInput(): Unit = runBlocking {
        val server=MockWebServer()
        val runtime=RuntimeConnection()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val opens=AtomicInteger()
        val snapshots=CopyOnWriteArrayList<JsonObject>()
        var lease=""
        var delayed:JsonObject?=null
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
            fun reply(socket:WebSocket, request:JsonObject, result:JsonObject) {
                socket.send(buildJsonObject {
                    put("id",request["id"]!!);put("protocol",1);put("ok",true);put("result",result.toString())
                }.toString())
            }
            override fun onMessage(webSocket:WebSocket,text:String) {
                val request=Json.parseToJsonElement(text).jsonObject
                val args=request["arguments"]!!.jsonObject
                val result=when(request["tool"]!!.jsonPrimitive.content) {
                    "gamepad.virtual_capabilities" -> buildJsonObject {put("supported",true);put("runtimeUid",0);put("mode","uinput")}
                    "gamepad.virtual_open" -> {
                        opens.incrementAndGet();lease=UUID.randomUUID().toString()
                        buildJsonObject {put("id",lease);put("mode","uinput");put("registered",true);put("gameVerified",false);put("deadmanMs",2000);put("deviceName",lease)}
                    }
                    "gamepad.virtual_close" -> buildJsonObject {put("closed",true)}
                    else -> {
                        snapshots+=args
                        if(opens.get()==1 && args["state"]!!.jsonObject["buttons"]!!.jsonArray.isNotEmpty()) {
                            delayed=request;return
                        }
                        delayed?.let { old ->
                            val oldArgs=old["arguments"]!!.jsonObject
                            reply(webSocket,old,buildJsonObject {put("id",oldArgs["id"]!!);put("sequence",oldArgs["sequence"]!!);put("submitted",true);put("gameVerified",false)})
                            delayed=null
                        }
                        buildJsonObject {put("id",args["id"]!!);put("sequence",args["sequence"]!!);put("submitted",true);put("gameVerified",false)}
                    }
                }
                reply(webSocket,request,result)
            }
        }))
        server.start()
        val controller=VirtualGamepadController(scope,runtime)
        try {
            runtime.connect(server.hostName,server.port)
            controller.start(true)
            val first=withTimeout(2000){controller.state.first{it.sequence>0}}
            assertTrue(controller.replace(VirtualGamepadInput(buttons=listOf("a"),leftX=.9)))
            val recovered=withTimeout(2500){controller.state.first{it.sequence>=first.sequence+2 && !it.reconnecting}}
            assertEquals(1,opens.get(),"One missed reply must not recreate a registered gamepad")
            assertEquals(first.deviceName,recovered.deviceName)
            assertTrue(recovered.running);assertFalse(recovered.reconnecting);assertNull(recovered.error)
            assertEquals(VirtualGamepadInput(),recovered.input)
            assertTrue(snapshots.last()["state"]!!.jsonObject["buttons"]!!.jsonArray.isEmpty())
            assertEquals(0.0,snapshots.last()["state"]!!.jsonObject["leftX"]!!.jsonPrimitive.double)
            assertTrue(runtime.connected.value)
            assertEquals(2,server.requestCount,"Recovery should reuse the original HTTP/WebSocket connection")
        } finally {
            controller.stop()
            withTimeout(2500){controller.state.first{!it.stopping}}
            scope.cancel();runtime.close();server.shutdown()
        }
    }
}
