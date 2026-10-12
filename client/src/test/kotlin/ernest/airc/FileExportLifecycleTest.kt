package ernest.airc
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.nio.file.Files
import kotlin.test.*

class FileExportLifecycleTest {
    @Test fun clearingTargetInvalidatesPendingChooserExportAndRemovesItsFile():Unit=verify(false)
    @Test fun claimedExportKeepsLocalOwnershipButCannotRestoreIntoNewGeneration():Unit=verify(true)
    private fun verify(claimed:Boolean):Unit=runBlocking {
        val server=MockWebServer();val runtime=RuntimeConnection();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val transfer=FileTransferController(scope,runtime);val cache=Files.createTempDirectory("airc-export-fixture-").toFile()
        val id="12345678-1234-1234-1234-123456789abc"
        val progress=TransferProgress(id,"download","a.txt",3,0,"ready","ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",null, "/transfers/$id/data")
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.path){
            "/status"->MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}""")
            "/ws"->MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){
                val r=Json.parseToJsonElement(text).jsonObject
                webSocket.send(buildJsonObject{put("id",r["id"]!!);put("protocol",1);put("ok",true);put("result",Json.encodeToString(TransferProgress.serializer(),progress))}.toString())
            }})
            else->MockResponse().setHeader("Content-Type","application/octet-stream").setBody("abc")
        }};server.start()
        try {
            runtime.connect(server.hostName,server.port);transfer.download("downloads","a.txt",cache,true)
            val ready=withTimeout(5000){transfer.state.first{!it.running&&(it.readyToSave||it.error!=null)}}
            assertNull(ready.error);assertEquals("a.txt",transfer.prepareExport());assertEquals(1,cache.listFiles()!!.size)
            val snapshot=if(claimed)checkNotNull(transfer.takeExport())else null
            transfer.clear()
            if(snapshot!=null){assertTrue(snapshot.file.exists());transfer.restoreExport(snapshot)}
            assertNull(transfer.takeExport());assertTrue(cache.listFiles()!!.isEmpty());assertFalse(transfer.state.value.readyToSave)
        }finally{transfer.close();runtime.close();scope.cancel();server.shutdown();cache.deleteRecursively()}
    }
}
