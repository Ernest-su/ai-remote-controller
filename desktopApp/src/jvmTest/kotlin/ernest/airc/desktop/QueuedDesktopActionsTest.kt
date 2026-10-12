package ernest.airc.desktop

import ernest.airc.RuntimeConnection
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class QueuedDesktopActionsTest {
    private class PausedDispatcher:CoroutineDispatcher() {
        private val queue=ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable){queue.add(block)}
        fun drain(){repeat(1000){(queue.poll() ?: return).run()};error("Dispatcher did not settle")}
    }

    @Test fun queuedActionsCannotFollowAReplacementRuntimeConnection():Unit=runBlocking {
        val dispatcher=PausedDispatcher()
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val runtime=RuntimeConnection()
        val directory=Files.createTempDirectory("airc-desktop-queue-")
        val received=CopyOnWriteArrayList<JsonObject>()
        val oldPollingStarted=CompletableDeferred<Unit>()
        val servers=listOf(MockWebServer(),MockWebServer())
        for(server in servers) {
            server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"same-id","tools":["remote.tap"],"modelConfigured":true}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onMessage(webSocket:WebSocket,text:String){
                    val request=Json.parseToJsonElement(text).jsonObject
                    if(server===servers[0] && request["tool"]?.jsonPrimitive?.content=="device.shell") {
                        oldPollingStarted.complete(Unit)
                        return
                    }
                    if(server===servers[1])received+=request
                    val result=if(request["tool"]?.jsonPrimitive?.content=="device.shell") "PlaybackState {state=3}" else ""
                    webSocket.send(buildJsonObject{put("id",request["id"]!!);put("protocol",1);put("ok",true);put("result",result)}.toString())
                }
            }));server.start()
        }
        val controller=DesktopController(store=DesktopProfileStore(directory),scope=scope,runtime=runtime,deviceNames=DesktopDeviceNames(directory))
        try {
            controller.connectLan(servers[0].hostName,servers[0].port)
            withTimeout(5000){while(!controller.state.value.connected||controller.state.value.busy){dispatcher.drain();delay(10)}}
            withTimeout(3000){while(!oldPollingStarted.isCompleted){dispatcher.drain();delay(10)}}
            controller.openApp("fixture.app")
            controller.key("home")
            assertTrue(controller.gesture("remote.tap",buildJsonObject{put("x",0.5);put("y",0.5)}))
            assertTrue(controller.text("owned queued task"))
            assertTrue(controller.state.value.touchBusy)
            assertTrue(controller.state.value.submitting)
            // Hold all four action coroutines until the underlying connection has changed.
            runtime.connect(servers[1].hostName,servers[1].port)
            dispatcher.drain()
            delay(100)
            dispatcher.drain()
            assertTrue(received.none{it["type"]?.jsonPrimitive?.content=="text" ||
                it["tool"]?.jsonPrimitive?.content in listOf("apps.open","remote.key","remote.tap")})
            withTimeout(3000){while(controller.state.value.media!="正在播放"){dispatcher.drain();delay(10)}}
            assertFalse(controller.state.value.touchBusy)
            assertFalse(controller.state.value.submitting)
            controller.openApp("fixture.after")
            withTimeout(3000){while(received.none{it["tool"]?.jsonPrimitive?.content=="apps.open"}){dispatcher.drain();delay(10)}}
            assertEquals(1,received.count{it["tool"]?.jsonPrimitive?.content=="apps.open"})
            assertTrue(runtime.connected.value)
        }finally{
            controller.close()
            servers.forEach{it.shutdown()}
            directory.toFile().deleteRecursively()
        }
    }
}
