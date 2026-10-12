package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import kotlin.test.*

class AnalysisBoundRequestTest {
    @Test fun apkInspectionRuntimeAdapterUsesBoundRequest()=verify(true)
    @Test fun storageAnalysisRuntimeAdapterUsesBoundRequest()=verify(false)
    private fun verify(apk:Boolean):Unit=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val runtime=RuntimeConnection();val server=MockWebServer()
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        val task=TransferProgress("fixture-task",if(apk)"apk_inspect" else "storage_scan","fixture.apk",3,0,"failed",error="fixture stopped",dataPath="/transfers/fixture-task/data",root="inbox",unit=if(apk)"bytes" else "items")
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){
            val message=Json.parseToJsonElement(text).jsonObject
            webSocket.send(buildJsonObject{put("id",message["id"]!!);put("protocol",1);put("ok",true);put("result",Json.encodeToString(TransferProgress.serializer(),task))}.toString())
        }}));server.start()
        var fallback=0
        val inspection=ApkInspectionController(scope,runtime){_,_->fallback++;error("unbound callback")}
        val analysis=StorageAnalysisController(scope,runtime){_,_->fallback++;error("unbound callback")}
        try {
            runtime.connect(server.hostName,server.port)
            val error=if(apk){inspection.inspect("inbox","fixture.apk",true);withTimeout(3000){inspection.state.first{!it.busy}}.error}
                else{analysis.analyze("inbox","fixture.apk",true);withTimeout(3000){analysis.state.first{!it.busy}}.error}
            assertEquals("fixture stopped",error);assertEquals(0,fallback)
        }finally{inspection.clear();analysis.clear();scope.cancel();runtime.close();server.shutdown()}
    }
}
