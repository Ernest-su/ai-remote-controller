package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import kotlin.test.*

class FileDeletionFailureTest {
    @Test fun progressFailureCancelsTheOriginalUnfinishedDeletion()=verify(false,false)
    @Test fun switchingPeerDuringProgressDoesNotCancelOnNewConnection()=verify(true,false)
    @Test fun reconnectingSamePeerDuringProgressDoesNotCancelOnNewGeneration()=verify(true,true)
    @Test fun mismatchedProgressTaskCannotReplaceTheOriginalDeletion()=verify(false,false,true)
    private fun verify(switchPeer:Boolean,samePeer:Boolean,swappedTask:Boolean=false): Unit = runBlocking {
        val cancelled = CompletableDeferred<String>()
        val progressEntered = CompletableDeferred<Unit>()
        val newRequests = java.util.concurrent.atomic.AtomicInteger()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message=Json.parseToJsonElement(text).jsonObject
                val tool=message["tool"]?.jsonPrimitive?.content
                val failed=tool=="task.progress"&&!swappedTask
                if(failed && switchPeer){progressEntered.complete(Unit);return}
                val payload=when {
                    tool=="files.delete" -> Json.encodeToString(FileActionProposal("proposal","delete","inbox","sample.bin",exists=true))
                    tool=="task.cancel" -> {
                        cancelled.complete(message["arguments"]!!.jsonObject["id"]!!.jsonPrimitive.content)
                        "{}"
                    }
                    tool=="task.progress"&&swappedTask -> Json.encodeToString(TransferProgress("other-task","delete","sample.bin",0,0,"completed",dataPath="/transfers/other-task/data",root="inbox",unit="items"))
                    else -> Json.encodeToString(TransferProgress("owned-task","delete","sample.bin",0,0,"deleting",dataPath="/transfers/owned-task/data",root="inbox",unit="items"))
                }
                webSocket.send(buildJsonObject {
                    put("id",message["id"]!!);put("protocol",1);put("ok",!failed)
                    if(failed)put("error","fixture progress failure")else put("result",payload)
                }.toString())
            }
        }))
        server.start()
        val next=if(samePeer)server else MockWebServer()
        if(switchPeer){
            next.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
            next.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onMessage(webSocket:WebSocket,text:String){newRequests.incrementAndGet()}
            }))
            if(!samePeer)next.start()
        }
        val runtime=RuntimeConnection()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val controller=FileTransferController(scope,runtime)
        try {
            runtime.connect(server.hostName,server.port)
            controller.delete("inbox","sample.bin",false,true)
            withTimeout(3000){controller.state.first{it.proposal!=null}}
            controller.confirm(true)
            if(switchPeer){
                withTimeout(3000){progressEntered.await()}
                runtime.connect(next.hostName,next.port)
            }
            val result=withTimeout(3000){controller.state.first{!it.running}}
            assertEquals("failed",result.phase)
            if(switchPeer){
                assertNotNull(result.error)
                assertFalse(cancelled.isCompleted)
                assertEquals(0,newRequests.get())
            }else{
                assertEquals(if(swappedTask)"删除任务响应不匹配"else"fixture progress failure",result.error)
                assertEquals("owned-task",withTimeout(1000){cancelled.await()})
            }
        } finally {controller.close();runtime.close();scope.cancel();server.shutdown();if(switchPeer&&!samePeer)next.shutdown()}
    }
}
