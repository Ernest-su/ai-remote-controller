package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import kotlin.test.*

class ScheduleControllerTest {
    @Test fun reconnectionInvalidatesManualConfirmationBeforeSending() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val runtime=RuntimeConnection()
        val server=MockWebServer();val received=java.util.concurrent.CopyOnWriteArrayList<String>();val saved=java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse =
            if(request.path=="/status")MockResponse().setBody("""{"protocol":1,"version":"fixture","deviceId":"timer-fixture","tools":[]}""")
            else MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){
                val call=Json.parseToJsonElement(text).jsonObject;received+=call["type"]!!.jsonPrimitive.content
                if(call["type"]!!.jsonPrimitive.content=="schedule_save") saved+=call["arguments"]!!.jsonObject
                val data=if(call["type"]!!.jsonPrimitive.content=="schedule_list") """{"tasks":[],"targetNow":1,"targetLocalTime":"2026-10-20 12:00","utcOffsetSeconds":28800}""" else "{}"
                webSocket.send(buildJsonObject {put("id",call["id"]!!);put("protocol",1);put("ok",true);put("result",data)}.toString())
            }})}
        server.start();val controller=ScheduleController(scope,runtime)
        try {
            runtime.connect(server.hostName,server.port)
            val old=withTimeout(3000){controller.state.first {it.sessionKey!=null}}.sessionKey
            controller.refresh();withTimeout(3000){controller.state.first {it.data!=null&&!it.busy}}
            runtime.disconnect();runtime.connect(server.hostName,server.port)
            withTimeout(3000){controller.state.first {it.sessionKey!=null&&it.sessionKey!=old}}
            controller.save("shutdown",null,"delay","1800",old)
            assertTrue(controller.state.value.error!!.contains("连接已改变"));assertFalse("schedule_save" in received)
            val current=controller.state.value.sessionKey
            controller.save("pause",null,"daily","20:00",current)
            withTimeout(3000){controller.state.first {!it.busy&&it.data!=null}}
            assertEquals(1,received.count {it=="schedule_save"})
            assertNull(saved.last()["volumePercent"])
            controller.save("set_volume",null,"delay","900",current,37)
            withTimeout(3000){controller.state.first {!it.busy}}
            assertEquals(37,saved.last()["volumePercent"]!!.jsonPrimitive.int)
            assertEquals("set_volume",saved.last()["action"]!!.jsonPrimitive.content)
            for(action in listOf("mute","unmute")) {
                controller.save(action,null,"daily","20:00",current)
                withTimeout(3000){controller.state.first {!it.busy}}
                assertEquals(action,saved.last()["action"]!!.jsonPrimitive.content)
                assertNull(saved.last()["volumePercent"])
            }
        } finally {controller.clear();scope.cancel();runtime.close();server.shutdown()}
    }
}
