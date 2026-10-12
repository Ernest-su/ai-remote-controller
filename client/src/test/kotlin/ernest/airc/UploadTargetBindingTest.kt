package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.*
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class UploadTargetBindingTest {
    @Test fun switchingConnectionDuringPreparationCannotUploadToNewPeer()=verify(false)
    @Test fun switchingConnectionDuringHashingStopsBeforeReadingAnotherChunk()=verify(true)
    private fun verify(blockRead:Boolean):Unit=runBlocking {
        val received=AtomicInteger()
        fun peer(id:String)=MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"$id","tools":[]}"""))
            enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){received.incrementAndGet()}}))
            start()
        }
        val original=peer("original");val next=peer("next")
        val runtime=RuntimeConnection();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val controller=FileTransferController(scope,runtime)
        val opening=CompletableDeferred<Unit>();val release=CountDownLatch(1)
        val reads=AtomicInteger();val closed=AtomicInteger()
        val input=object:InputStream(){
            override fun read():Int=error("Expected chunked reads")
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                reads.incrementAndGet()
                if(blockRead){opening.complete(Unit);release.await()}
                buffer.fill(1,offset,offset+length)
                return length
            }
            override fun close(){closed.incrementAndGet()}
        }
        try {
            runtime.connect(original.hostName,original.port)
            controller.select(UploadSource("sample.bin",null){if(!blockRead){opening.complete(Unit);release.await()};input})
            controller.upload("inbox","sample.bin",true)
            withTimeout(3000){opening.await()}
            runtime.connect(next.hostName,next.port)
            release.countDown()
            val result=withTimeout(3000){controller.state.first{!it.running}}
            assertEquals("failed",result.phase)
            assertEquals("目标连接已改变",result.error)
            assertEquals(0,received.get())
            assertEquals(if(blockRead)1 else 0,reads.get())
            assertEquals(1,closed.get())
        } finally {
            release.countDown();controller.close();runtime.close();scope.cancel();original.shutdown();next.shutdown()
        }
    }
}
