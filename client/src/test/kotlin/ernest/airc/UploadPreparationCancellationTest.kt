package ernest.airc

import okhttp3.*
import okhttp3.mockwebserver.*
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class UploadPreparationCancellationTest {
    @Test fun cancellationClosesSourceReturnedByLateOpen()=verify(true)
    @Test fun cancellationClosesSourceBlockedDuringHashing()=verify(false)
    @Test fun overwriteHashingCanBeCancelledBeforeRequestingApproval()=verify(false,true)
    private fun verify(blockOpen:Boolean,overwrite:Boolean=false):Unit=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val runtime=RuntimeConnection()
        val server=MockWebServer()
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){}));server.start()
        val controller=FileTransferController(scope,runtime)
        val entered=CompletableDeferred<Unit>()
        val release=CountDownLatch(1)
        val closed=CountDownLatch(1)
        val input=object:InputStream(){
            override fun read():Int { entered.complete(Unit);closed.await();throw IOException("fixture closed") }
            override fun close(){closed.countDown()}
        }
        try {
            runtime.connect(server.hostName,server.port)
            controller.select(UploadSource("blocked.bin",1){if(blockOpen){entered.complete(Unit);release.await()};input})
            controller.upload("inbox","blocked.bin",true,overwrite)
            withTimeout(3000){entered.await()}
            assertNull(controller.state.value.proposal)
            controller.cancel()
            release.countDown()
            val result=withTimeout(2000){controller.state.first{!it.running}}
            assertEquals("cancelled",result.phase)
            assertEquals(0L,closed.count)
            assertNull(result.error)
        } finally {
            release.countDown();input.close();controller.close();runtime.close();scope.cancel();server.shutdown()
        }
    }
}
