package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.nio.file.Files
import kotlin.test.*

class DownloadTaskIdentityTest {
    @Test fun substitutedProgressIdIsRejectedAndOnlyOriginalTaskIsCancelled():Unit=runBlocking {
        val original="11111111-1111-1111-1111-111111111111"
        val other="22222222-2222-2222-2222-222222222222"
        val server=MockWebServer()
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onMessage(webSocket:WebSocket,text:String){
                val request=Json.parseToJsonElement(text).jsonObject
                val progress=request["tool"]?.jsonPrimitive?.content=="task.progress"
                val id=if(progress)other else original
                val result=TransferProgress(id,"download","sample.bin",3,0,if(progress)"ready"else"hashing",sha256=if(progress)"0".repeat(64)else null,dataPath="/transfers/$id/data",root="inbox")
                webSocket.send(buildJsonObject{put("id",request["id"]!!);put("protocol",1);put("ok",true);put("result",Json.encodeToString(result))}.toString())
            }
        }))
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        val runtime=RuntimeConnection();val transfer=RuntimeTransferClient(runtime)
        val cache=Files.createTempDirectory("airc-download-identity-").toFile()
        try{
            runtime.connect(server.hostName,server.port)
            val error=assertFailsWith<IllegalStateException>{withTimeout(5000){transfer.download("sample.bin",cache,"inbox")}}
            assertEquals("传输任务 ID 不匹配",error.message)
            server.takeRequest();server.takeRequest()
            val cancel=server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(cancel);assertEquals("DELETE",cancel.method);assertEquals("/transfers/$original",cancel.path)
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }finally{transfer.close();runtime.close();server.shutdown();cache.deleteRecursively()}
    }
}
