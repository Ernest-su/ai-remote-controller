package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CacheApprovalTargetTest {
    @Test fun preparedCacheCannotConfirmOnNewPeer()=verify("prepare",false)
    @Test fun preparedCacheCannotConfirmAfterSamePeerReconnect()=verify("prepare",true)
    @Test fun pendingCacheCannotConfirmOnNewPeer()=verify("pending",false)
    @Test fun pendingCacheCannotConfirmAfterSamePeerReconnect()=verify("pending",true)
    private fun verify(kind:String,samePeer:Boolean):Unit=runBlocking {
        val approvals=AtomicInteger();val newRequests=AtomicInteger()
        fun MockWebServer.enqueueConnection(original:Boolean) {
            enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"$original","tools":[]}"""))
            enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){
                if(!original)newRequests.incrementAndGet()
                val message=Json.parseToJsonElement(text).jsonObject
                if(message["type"]?.jsonPrimitive?.content=="approve_cache")approvals.incrementAndGet()
                val app=TargetAppInfo("fixture.app",0,10001,1,codePath="/data/app/fixture",lastUpdateTime="fixture",system=false)
                val proposal=AppCacheProposal("fixture-proposal","app_cache_clear",app,true,true,"package_manager_cache_only")
                val payload=if(message["tool"]?.jsonPrimitive?.content=="apps.cache_pending")Json.encodeToString(listOf(proposal))else Json.encodeToString(AppCacheProposal.serializer(),proposal)
                webSocket.send(buildJsonObject {put("id",message["id"]!!);put("protocol",1);put("ok",true);put("result",payload)}.toString())
            }}))
        }
        val first=MockWebServer();first.enqueueConnection(true);first.start()
        val second=if(samePeer)first else MockWebServer()
        second.enqueueConnection(false);if(!samePeer)second.start()
        val runtime=RuntimeConnection()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val controller=AppCacheController(scope,runtime)
        try {
            runtime.connect(first.hostName,first.port)
            if(kind=="pending")controller.pending(true)else controller.prepare("fixture.app",true)
            withTimeout(3000){controller.state.first{it.proposal!=null}}
            runtime.connect(second.hostName,second.port)
            controller.confirm(true)
            val result=withTimeout(3000){controller.state.first{!it.busy}}
            assertEquals("目标连接已改变",result.error)
            assertEquals(0,approvals.get());assertEquals(0,newRequests.get());assertNull(result.proposal)
        } finally {controller.clear();runtime.close();scope.cancel();first.shutdown();if(!samePeer)second.shutdown()}
    }
}
