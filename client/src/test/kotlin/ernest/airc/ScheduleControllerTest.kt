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
        val quietReply=java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
        val manual=java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        val server=MockWebServer();val received=java.util.concurrent.CopyOnWriteArrayList<String>();val saved=java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse =
            if(request.path=="/status")MockResponse().setBody("""{"protocol":1,"version":"fixture","deviceId":"timer-fixture","tools":[]}""")
            else MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){
                val call=Json.parseToJsonElement(text).jsonObject;received+=call["type"]!!.jsonPrimitive.content
                if(call["type"]!!.jsonPrimitive.content=="schedule_run_once") manual+=call["arguments"]!!.jsonObject
                if(call["type"]!!.jsonPrimitive.content=="schedule_save") saved+=call["arguments"]!!.jsonObject
                val data=if(call["type"]!!.jsonPrimitive.content=="schedule_list") """{"tasks":[],"targetNow":1,"targetLocalTime":"2026-10-20 12:00","utcOffsetSeconds":28800}""" else "{}"
                val reply = { webSocket.send(buildJsonObject {put("id",call["id"]!!);put("protocol",1);put("ok",true);put("result",data)}.toString()); Unit }
                if (call["type"]!!.jsonPrimitive.content == "schedule_list" && received.count { it == "schedule_list" } == 2) quietReply.set(reply) else reply()
            }})}
        server.start();val controller=ScheduleController(scope,runtime)
        try {
            runtime.connect(server.hostName,server.port)
            val old=withTimeout(3000){controller.state.first {it.sessionKey!=null}}.sessionKey
            controller.refresh();withTimeout(3000){controller.state.first {it.data!=null&&!it.busy&&!it.refreshing}}
            controller.refreshQuietly()
            withTimeout(3000) { while(quietReply.get()==null) delay(10) }
            assertFalse(controller.state.value.busy)
            assertFalse(controller.state.value.refreshing)
            controller.runOnce("fixture-id", old)
            withTimeout(3000) { controller.state.first { !it.busy } }
            assertTrue("schedule_run_once" in received)
            assertEquals("fixture-id", manual.single()["id"]!!.jsonPrimitive.content)
            assertTrue(manual.single()["confirmed"]!!.jsonPrimitive.boolean)
            quietReply.getAndSet(null)?.invoke()
            runtime.disconnect();runtime.connect(server.hostName,server.port)
            withTimeout(3000){controller.state.first {it.sessionKey!=null&&it.sessionKey!=old}}
            controller.save("shutdown",null,"delay","1800",old)
            assertTrue(controller.state.value.error!!.contains("连接已改变"));assertFalse("schedule_save" in received)
            controller.runOnce("fixture-id", old)
            assertEquals(1, received.count { it == "schedule_run_once" })
            val current=controller.state.value.sessionKey
            controller.save("pause",null,"daily","20:00",current)
            withTimeout(3000){controller.state.first {!it.busy&&it.data!=null}}
            assertEquals(1,received.count {it=="schedule_save"})
            assertNull(saved.last()["volumePercent"])
            controller.save("set_volume",null,"delay","900",current,37)
            withTimeout(3000){controller.state.first {!it.busy}}
            assertEquals(37,saved.last()["volumePercent"]!!.jsonPrimitive.int)
            assertEquals("set_volume",saved.last()["action"]!!.jsonPrimitive.content)
            controller.update("existing-task", "screen_off", null, "delay", "5400", current, null)
            withTimeout(3000){controller.state.first {!it.busy}}
            assertEquals("existing-task", saved.last()["id"]!!.jsonPrimitive.content)
            assertEquals("screen_off",saved.last()["action"]!!.jsonPrimitive.content)
            assertEquals(5400,saved.last()["delaySeconds"]!!.jsonPrimitive.int)
            assertTrue(controller.state.value.saveRevision >= 3)
            for(action in listOf("mute","unmute")) {
                controller.save(action,null,"daily","20:00",current)
                withTimeout(3000){controller.state.first {!it.busy}}
                assertEquals(action,saved.last()["action"]!!.jsonPrimitive.content)
                assertNull(saved.last()["volumePercent"])
            }
        } finally {controller.clear();scope.cancel();runtime.close();server.shutdown()}
    }
}
