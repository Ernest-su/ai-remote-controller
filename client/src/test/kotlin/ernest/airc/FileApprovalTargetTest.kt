package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class FileApprovalTargetTest {
    @Test fun recursiveProposalCannotReplaceNonRecursiveDeletion()=verify("delete",false,true)
    @Test fun oldDeleteConfirmationCannotReachNewConnection()=verify("delete",false)
    @Test fun oldDeleteConfirmationCannotSurviveSamePeerReconnect()=verify("delete",true)
    @Test fun oldOverwriteConfirmationCannotReachNewConnection()=verify("overwrite",false)
    @Test fun oldOverwriteConfirmationCannotSurviveSamePeerReconnect()=verify("overwrite",true)
    @Test fun pendingModelDeleteCannotReachNewConnection()=verify("pending",false)
    @Test fun pendingModelDeleteCannotSurviveSamePeerReconnect()=verify("pending",true)
    private fun verify(kind:String,samePeer:Boolean,mismatchedRecursive:Boolean=false):Unit=runBlocking {
        val approvals=AtomicInteger();val newRequests=AtomicInteger()
        fun MockWebServer.enqueueConnection(original:Boolean) {
            enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"$original","tools":[]}"""))
            enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){
                if(!original)newRequests.incrementAndGet()
                val message=Json.parseToJsonElement(text).jsonObject
                if(message["type"]?.jsonPrimitive?.content=="approve")approvals.incrementAndGet()
                val proposal=FileActionProposal("fixture-proposal",if(kind=="overwrite")"overwrite" else "delete","inbox","sample.txt",exists=true,recursive=mismatchedRecursive)
                val payload=if(message["tool"]?.jsonPrimitive?.content=="files.pending")Json.encodeToString(listOf(proposal))else Json.encodeToString(FileActionProposal.serializer(),proposal)
                webSocket.send(buildJsonObject {put("id",message["id"]!!);put("protocol",1);put("ok",true);put("result",payload)}.toString())
            }}))
        }
        val first=MockWebServer();first.enqueueConnection(true);first.start()
        val second=if(samePeer)first else MockWebServer()
        second.enqueueConnection(false);if(!samePeer)second.start()
        val runtime=RuntimeConnection()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val controller=FileTransferController(scope,runtime)
        try {
            runtime.connect(first.hostName,first.port)
            when(kind){
                "overwrite"->{controller.select(UploadSource("sample.txt",3){byteArrayOf(1,2,3).inputStream()});controller.upload("inbox","sample.txt",true,true)}
                "pending"->controller.reviewPending(true)
                else->controller.delete("inbox","sample.txt",false,true)
            }
            if(mismatchedRecursive){
                val result=withTimeout(3000){controller.state.first{!it.running}}
                assertEquals("操作确认信息不匹配",result.error)
                assertNull(result.proposal)
                assertEquals(0,approvals.get())
                return@runBlocking
            }
            withTimeout(3000){controller.state.first{it.proposal!=null}}
            runtime.connect(second.hostName,second.port)
            controller.confirm(true)
            val result=withTimeout(3000){controller.state.first{!it.running}}
            assertEquals("目标连接已改变",result.error)
            assertEquals(0,approvals.get());assertEquals(0,newRequests.get());assertNull(result.proposal)
        } finally {controller.close();runtime.close();scope.cancel();first.shutdown();if(!samePeer)second.shutdown()}
    }
}
