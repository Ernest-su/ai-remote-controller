package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AppApprovalTargetTest {
    @Test fun uninstallCannotConfirmOnDifferentPeer()=verify("uninstall",false)
    @Test fun uninstallCannotConfirmAfterSamePeerReconnect()=verify("uninstall",true)
    @Test fun pendingInstallCannotConfirmOnDifferentPeer()=verify("install",false)
    @Test fun pendingInstallCannotConfirmAfterSamePeerReconnect()=verify("install",true)
    private fun verify(kind:String,samePeer:Boolean):Unit=runBlocking {
        val approvals=AtomicInteger();val newRequests=AtomicInteger()
        fun MockWebServer.enqueueConnection(original:Boolean) {
            enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"$original","tools":[]}"""))
            enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){
                if(!original)newRequests.incrementAndGet()
                val message=Json.parseToJsonElement(text).jsonObject
                if(message["type"]?.jsonPrimitive?.content?.startsWith("approve")==true)approvals.incrementAndGet()
                val app=TargetAppInfo("fixture.app",0,10001,1,codePath="/data/app/fixture",lastUpdateTime="fixture",system=false)
                val apk=TargetApkInfo("inbox","fixture.apk","fixture.app",1,sizeBytes=3,sha256="0".repeat(64),signatures=emptyList(),signatureVerified=false)
                val payload=if(kind=="install")Json.encodeToString(listOf(AppInstallProposal("fixture-proposal","app_install",apk,userId=0,update=false,approvalRequired=true,codeMayAffectAllUsers=true)))
                    else Json.encodeToString(AppUninstallProposal.serializer(),AppUninstallProposal("fixture-proposal","app_uninstall",app,true,true))
                webSocket.send(buildJsonObject {put("id",message["id"]!!);put("protocol",1);put("ok",true);put("result",payload)}.toString())
            }}))
        }
        val first=MockWebServer();first.enqueueConnection(true);first.start()
        val second=if(samePeer)first else MockWebServer()
        second.enqueueConnection(false);if(!samePeer)second.start()
        val runtime=RuntimeConnection()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val management=AppManagementController(scope,runtime);val installation=AppInstallationController(scope,runtime)
        try {
            runtime.connect(first.hostName,first.port)
            if(kind=="install"){installation.pending(true);withTimeout(3000){installation.state.first{it.proposal!=null}}}
            else {management.uninstall("fixture.app",true);withTimeout(3000){management.state.first{it.proposal!=null}}}
            runtime.connect(second.hostName,second.port)
            if(kind=="install"){
                installation.confirm(true)
                val result=withTimeout(3000){installation.state.first{!it.busy}}
                assertEquals("目标连接已改变",result.error);assertNull(result.proposal)
            } else {
                management.confirm(true)
                val result=withTimeout(3000){management.state.first{!it.busy}}
                assertEquals("目标连接已改变",result.error);assertNull(result.proposal)
            }
            assertEquals(0,approvals.get());assertEquals(0,newRequests.get())
        } finally {management.clear();installation.clear();runtime.close();scope.cancel();first.shutdown();if(!samePeer)second.shutdown()}
    }
}
