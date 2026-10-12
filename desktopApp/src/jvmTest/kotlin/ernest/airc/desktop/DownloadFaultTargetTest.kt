package ernest.airc.desktop

import ernest.airc.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class DownloadFaultTargetTest {
    @Test fun interruptedDownloadRemovesLocalPartialAndPreservesRemoteSource()=verify(false)
    @Test fun cancelledDownloadRemovesCacheAndKeepsConnectionAndSource()=verify(true)
    private fun verify(cancel:Boolean):Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for controlled download interruption",!serial.isNullOrBlank())
        val target=ShellRuntimeTarget(serial!!);val runtime=RuntimeConnection();val transfer=RuntimeTransferClient(runtime)
        val directory=Files.createTempDirectory("airc-download-fault-").toFile()
        val source=java.io.File(directory,"source.bin");val cache=java.io.File(directory,"cache").apply{mkdir()}
        val size=17L*1024*1024;val buffer=ByteArray(64*1024);val digest=MessageDigest.getInstance("SHA-256")
        source.outputStream().use{output->repeat((size/buffer.size).toInt()){output.write(buffer);digest.update(buffer)}}
        val sha=digest.digest().joinToString(""){(it.toInt()and 255).toString(16).padStart(2,'0')}
        val gate=java.util.concurrent.CountDownLatch(1);val reached=CompletableDeferred<Unit>();var downloading:Deferred<Result<Pair<String,java.io.File>>>?=null
        var relay:TcpFaultRelay?=null;val cut=AtomicBoolean(false);val sawPartial=AtomicBoolean(false)
        try {
            target.prepare();val active=TcpFaultRelay(target.port!!);relay=active
            runtime.connect("127.0.0.1",active.port)
            withTimeout(30000){transfer.upload("source.bin",size,sha,source::inputStream)}
            downloading=async(Dispatchers.IO){runCatching{transfer.download("source.bin",cache,"inbox"){progress->
                if(progress.phase=="downloading"&&progress.bytesTransferred>=512*1024&&cut.compareAndSet(false,true)){
                    sawPartial.set(cache.listFiles().orEmpty().any{it.length()>=512*1024})
                    if(cancel){reached.complete(Unit);gate.await()}else active.cut()
                }
            }}}
            if(cancel){
                withTimeout(30000){reached.await()};downloading.cancel();gate.countDown()
                withTimeout(10000){downloading.join()};assertTrue(downloading.isCancelled)
            }else assertTrue(withTimeout(30000){downloading.await()}.isFailure)
            assertTrue(cut.get());assertTrue(sawPartial.get())
            assertTrue(cache.listFiles().orEmpty().isEmpty(),"Download must remove its unverified cache")
            if(cancel)assertTrue(runtime.connected.value)
            else {withTimeout(5000){runtime.connected.first{!it}};active.restore();runtime.connect("127.0.0.1",active.port)}
            val stat=Json.decodeFromString<TargetFileStat>(runtime.request("tool","files.stat",buildJsonObject{put("root","inbox");put("path","source.bin")}))
            assertEquals(size,stat.sizeBytes)
            val recovered=withTimeout(30000){transfer.download("source.bin",cache,"inbox")}
            val actual=MessageDigest.getInstance("SHA-256")
            recovered.second.inputStream().use{input->while(true){val n=input.read(buffer);if(n<0)break;actual.update(buffer,0,n)}}
            assertEquals(sha,actual.digest().joinToString(""){(it.toInt()and 255).toString(16).padStart(2,'0')})
        }finally{gate.countDown();downloading?.cancelAndJoin();transfer.close();runtime.close();relay?.close();try{target.close()}finally{directory.deleteRecursively()}}
    }
}
