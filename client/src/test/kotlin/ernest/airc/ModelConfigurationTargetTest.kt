package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ModelConfigurationTargetTest {
    @Test fun samePeerReconnectRejectsOldConfigurationWriter()=verify(true,true)
    @Test fun newPeerRejectsOldConfigurationWriter()=verify(true,false)
    @Test fun originalConnectionAcceptsItsConfigurationWriter()=verify(false,false)
    private fun verify(reconnect:Boolean,samePeer:Boolean):Unit=runBlocking {
        val requests=AtomicInteger()
        fun MockWebServer.connection(){
            enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
            enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onMessage(webSocket:WebSocket,text:String){
                    requests.incrementAndGet()
                    val request=Json.parseToJsonElement(text).jsonObject
                    assertEquals("configure",request["type"]!!.jsonPrimitive.content)
                    webSocket.send(buildJsonObject{put("id",request["id"]!!);put("protocol",1);put("ok",true);put("result","")}.toString())
                }
            }))
        }
        val first=MockWebServer();first.connection();first.start()
        val next=if(samePeer)first else MockWebServer()
        val runtime=RuntimeConnection()
        try{
            runtime.connect(first.hostName,first.port)
            val write=runtime.configurationWriter()
            val profile=ModelProfile("https://api.test/v1","fixture-key","Model")
            if(reconnect){
                next.connection();if(!samePeer)next.start()
                runtime.connect(next.hostName,next.port)
                assertEquals("目标连接已改变",assertFailsWith<IllegalStateException>{write(profile)}.message)
                assertEquals(0,requests.get())
            }else{write(profile);assertEquals(1,requests.get())}
        }finally{runtime.close();first.shutdown();if(reconnect&&!samePeer)next.shutdown()}
    }
}
