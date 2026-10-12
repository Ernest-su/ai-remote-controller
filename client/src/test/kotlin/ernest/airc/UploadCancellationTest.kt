package ernest.airc
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.test.*
class UploadCancellationTest {
    @Test fun cancelClosesSourceBlockedInRead()=blockedSource(false)
    @Test fun clientCloseClosesSourceBlockedInRead()=blockedSource(true)
    @Test fun sourceOpenedAfterCancellationIsClosed():Unit=runBlocking {
        val guard=UploadInputGuard();val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1);val closed=CountDownLatch(1)
        val opening=async(Dispatchers.IO){runCatching{guard.open { entered.complete(Unit);release.await();object:InputStream(){override fun read()=-1;override fun close(){closed.countDown()}} }}}
        try{withTimeout(3000){entered.await()};guard.close();release.countDown();assertIs<IOException>(withTimeout(2000){opening.await()}.exceptionOrNull());assertEquals(0L,closed.count)}
        finally{release.countDown();guard.close();opening.cancelAndJoin()}
    }
    private fun blockedSource(closeClient:Boolean):Unit=runBlocking {
        val server=MockWebServer();val runtime=RuntimeConnection();val client=RuntimeTransferClient(runtime)
        val reading=CompletableDeferred<Unit>();val closed=CountDownLatch(1)
        val input=object:InputStream(){
            override fun read():Int{reading.complete(Unit);closed.await();throw IOException("fixture source closed")}
            override fun close(){closed.countDown()}
        }
        val id="12345678-1234-1234-1234-123456789abc"
        val progress=TransferProgress(id,"upload","blocked.bin",1,0,"awaiting_upload","0".repeat(64),dataPath="/transfers/$id/data",root="inbox")
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.path){
            "/status"->MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}""")
            "/ws"->MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){val r=Json.parseToJsonElement(text).jsonObject;webSocket.send(buildJsonObject{put("id",r["id"]!!);put("protocol",1);put("ok",true);put("result",Json.encodeToString(TransferProgress.serializer(),progress))}.toString())}})
            else->MockResponse().setBody("{}")
        }};server.start()
        var uploading:Job?=null
        try{
            runtime.connect(server.hostName,server.port)
            uploading=launch(Dispatchers.IO){try{client.upload("blocked.bin",1,"0".repeat(64),{input});fail("blocked upload succeeded")}catch(error:Exception){if(!closeClient && error !is CancellationException)throw error}}
            withTimeout(3000){reading.await()}
            withTimeout(2000){if(closeClient){client.close();uploading.join()}else uploading.cancelAndJoin()}
            assertEquals(0L,closed.count)
        }finally{input.close();uploading?.cancelAndJoin();client.close();runtime.close();server.shutdown()}
    }
}
