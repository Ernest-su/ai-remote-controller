package ernest.airc
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import kotlin.test.*
class SystemActionControllerTest {
    @Test fun approvalRequiresUserInputAndRejectionNeverSubmitsAccepted():Unit=runBlocking {
        val server=MockWebServer();val runtime=RuntimeConnection();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var invalidations=0
        val controller=SystemActionController(scope,runtime,{invalidations++});val approvals=java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val id="12345678-1234-1234-1234-123456789abc"
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){
            val r=Json.parseToJsonElement(text).jsonObject
            val result=if(r["type"]!!.jsonPrimitive.content=="approve_system") {
                val accepted=r["arguments"]!!.jsonObject["accepted"]!!.jsonPrimitive.boolean;approvals+=accepted
                if(accepted) """{"id":"$id","action":"reboot","submitted":true,"completed":false}""" else """{"id":"$id","rejected":true}"""
            }else """{"id":"$id","kind":"system_action","action":"reboot","runtimeUid":0,"approvalRequired":true,"connectionMayClose":true,"expiresInSeconds":120}"""
            webSocket.send(buildJsonObject{put("id",r["id"]!!);put("protocol",1);put("ok",true);put("result",result)}.toString())
        }}));server.start()
        try {
            runtime.connect(server.hostName,server.port)
            controller.prepare("reboot",false);assertFalse(controller.state.value.busy)
            controller.prepare("reboot",true);withTimeout(3000){controller.state.first{it.proposal!=null}}
            assertTrue(approvals.isEmpty());controller.confirm(false)
            withTimeout(3000){controller.state.first{!it.busy}};assertEquals(listOf(false),approvals.toList());assertEquals(0,invalidations)
            controller.prepare("reboot",true);withTimeout(3000){controller.state.first{it.proposal!=null}};controller.confirm(true)
            val state=withTimeout(3000){controller.state.first{!it.busy}};assertNull(state.error);assertTrue(state.message!!.contains("请求已提交"));assertEquals(listOf(false,true),approvals.toList());assertEquals(1,invalidations)
            assertTrue(state.requiresVerification)
            controller.clear();controller.clear()
            assertFalse(controller.state.value.busy);assertNull(controller.state.value.proposal)
            assertTrue(controller.state.value.requiresVerification)
            assertTrue(controller.state.value.message!!.contains("先前目标"))
        }finally{controller.clear();runtime.close();scope.cancel();server.shutdown()}
    }
    @Test fun clearingPendingProposalBeforeReconnectCannotApproveNewTarget():Unit=runBlocking {
        val first=MockWebServer();val second=MockWebServer();val runtime=RuntimeConnection()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val controller=SystemActionController(scope,runtime)
        val approvals=java.util.concurrent.CopyOnWriteArrayList<String>()
        val id="12345678-1234-1234-1234-123456789abc"
        fun configure(server:MockWebServer,name:String){
            server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"$name","tools":[]}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){
                val r=Json.parseToJsonElement(text).jsonObject
                val result=if(r["type"]!!.jsonPrimitive.content=="approve_system"){
                    approvals+=name+":"+r["arguments"]!!.jsonObject["accepted"]!!.jsonPrimitive.content
                    """{"id":"$id","rejected":true}"""
                }else """{"id":"$id","kind":"system_action","action":"lock","runtimeUid":0,"approvalRequired":true,"connectionMayClose":false,"expiresInSeconds":120}"""
                webSocket.send(buildJsonObject{put("id",r["id"]!!);put("protocol",1);put("ok",true);put("result",result)}.toString())
            }}));server.start()
        }
        configure(first,"first");configure(second,"second")
        try {
            runtime.connect(first.hostName,first.port);controller.prepare("lock",true)
            withTimeout(3000){controller.state.first{it.proposal!=null}}
            controller.clear();runtime.disconnect();runtime.connect(second.hostName,second.port)
            controller.confirm(true)
            // Send a new-target request after the late confirmation; FIFO server delivery
            // proves no old approval preceded this request on the new socket.
            runtime.request("tool","device.system_pending")
            assertNull(controller.state.value.proposal);assertFalse(controller.state.value.busy)
            assertTrue(approvals.none{it=="first:true"||it.startsWith("second:")})
        }finally{controller.clear();runtime.close();scope.cancel();first.shutdown();second.shutdown()}
    }

    @Test fun remainingLifetimeTimeoutRejectsInsteadOfSubmitting():Unit=runBlocking {
        val server=MockWebServer();val runtime=RuntimeConnection();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val controller=SystemActionController(scope,runtime);val decision=CompletableDeferred<Boolean>()
        val id="12345678-1234-1234-1234-123456789abc"
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){
            val r=Json.parseToJsonElement(text).jsonObject
            val result=if(r["type"]!!.jsonPrimitive.content=="approve_system"){
                decision.complete(r["arguments"]!!.jsonObject["accepted"]!!.jsonPrimitive.boolean)
                """{"id":"$id","rejected":true}"""
            }else """[{"id":"$id","kind":"system_action","action":"lock","runtimeUid":0,"approvalRequired":true,"connectionMayClose":false,"expiresInSeconds":1}]"""
            webSocket.send(buildJsonObject{put("id",r["id"]!!);put("protocol",1);put("ok",true);put("result",result)}.toString())
        }}));server.start()
        try {
            runtime.connect(server.hostName,server.port);controller.pending(true)
            assertFalse(withTimeout(4000){decision.await()})
            val state=withTimeout(3000){controller.state.first{!it.busy}}
            assertNull(state.proposal);assertTrue(state.error!!.contains("过期"))
        }finally{controller.clear();runtime.close();scope.cancel();server.shutdown()}
    }

    @Test fun staleOnlineFlagReportsDisconnectedWithoutThrowingOrInvalidatingObservation():Unit=runBlocking {
        val runtime=RuntimeConnection();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var invalidations=0
        val controller=SystemActionController(scope,runtime,{invalidations++})
        try{
            controller.prepare("lock",true)
            assertFalse(controller.state.value.busy);assertNull(controller.state.value.proposal)
            assertTrue(controller.state.value.error!!.contains("连接设备"))
            controller.pending(true);controller.confirm(true)
            assertEquals(0,invalidations);assertFalse(controller.state.value.requiresVerification)
        }finally{controller.clear();runtime.close();scope.cancel()}
    }

    @Test fun lateProposalReplyAfterClearCannotPublishDialogOrSubmit():Unit=runBlocking {
        val server=MockWebServer();val runtime=RuntimeConnection();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val controller=SystemActionController(scope,runtime);val prepared=CompletableDeferred<Pair<WebSocket,JsonElement>>()
        val approvals=java.util.concurrent.atomic.AtomicInteger()
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){
            val r=Json.parseToJsonElement(text).jsonObject
            if(r["tool"]?.jsonPrimitive?.content=="device.system_action"){prepared.complete(webSocket to r["id"]!!);return}
            if(r["type"]!!.jsonPrimitive.content=="approve_system")approvals.incrementAndGet()
            webSocket.send(buildJsonObject{put("id",r["id"]!!);put("protocol",1);put("ok",true);put("result","[]")}.toString())
        }}));server.start()
        try {
            runtime.connect(server.hostName,server.port);controller.prepare("lock",true)
            val (socket,id)=withTimeout(3000){prepared.await()};controller.clear()
            val proposal="""{"id":"12345678-1234-1234-1234-123456789abc","kind":"system_action","action":"lock","runtimeUid":0,"approvalRequired":true,"connectionMayClose":false,"expiresInSeconds":120}"""
            socket.send(buildJsonObject{put("id",id);put("protocol",1);put("ok",true);put("result",proposal)}.toString())
            runtime.request("tool","device.system_pending");controller.confirm(true)
            assertNull(controller.state.value.proposal);assertFalse(controller.state.value.busy)
            assertFalse(controller.state.value.requiresVerification);assertEquals(0,approvals.get())
        }finally{controller.clear();runtime.close();scope.cancel();server.shutdown()}
    }

}
