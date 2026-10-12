package ernest.airc.desktop

import ernest.airc.*
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class UploadFaultTargetTest {
    @Test fun interruptedUploadNeverCommitsAndCleansTemporaryFile()=verify(false)
    @Test fun interruptedOverwritePreservesOriginalAndCleansTemporaryFile()=verify(true)
    @Test fun cancelledUploadCleansTemporaryAndKeepsConnection()=verify(false,true)
    @Test fun cancelledOverwritePreservesOriginalAndKeepsConnection()=verify(true,true)
    private fun verify(overwrite:Boolean,cancel:Boolean=false):Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for controlled upload interruption",!serial.isNullOrBlank())
        val target=ShellRuntimeTarget(serial!!);val runtime=RuntimeConnection();val transfer=RuntimeTransferClient(runtime)
        var relay:TcpFaultRelay?=null;var upload:Deferred<Result<TransferProgress>>?=null
        val taskId=java.util.concurrent.atomic.AtomicReference<String>()
        val gate=CountDownLatch(1);val closed=AtomicBoolean(false);val sending=CompletableDeferred<Unit>()
        val size=17L*1024*1024;val buffer=ByteArray(64*1024);val digest=MessageDigest.getInstance("SHA-256")
        repeat((size/buffer.size).toInt()){digest.update(buffer)}
        val sha=digest.digest().joinToString(""){(it.toInt()and 255).toString(16).padStart(2,'0')}
        val input=object:InputStream(){
            var remaining=size
            override fun read():Int=error("bulk read required")
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                if(size-remaining>=512*1024)gate.await()
                if(closed.get())throw IOException("fixture source closed")
                if(remaining==0L)return -1
                val count=minOf(length.toLong(),remaining).toInt();bytes.fill(0,offset,offset+count);remaining-=count;return count
            }
            override fun close(){closed.set(true);gate.countDown()}
        }
        try {
            target.prepare();relay=TcpFaultRelay(target.port!!)
            runtime.connect("127.0.0.1",relay.port)
            var overwriteToken:String?=null
            if(overwrite){
                val original="original".toByteArray()
                val originalSha=MessageDigest.getInstance("SHA-256").digest(original).joinToString(""){(it.toInt()and 255).toString(16).padStart(2,'0')}
                transfer.upload("interrupted.bin",original.size.toLong(),originalSha,{original.inputStream()})
                val proposal=Json.parseToJsonElement(runtime.request("tool","files.prepare_overwrite",buildJsonObject{put("root","inbox");put("path","interrupted.bin")})).jsonObject
                val approved=Json.parseToJsonElement(runtime.request("approve",arguments=buildJsonObject{put("id",proposal["id"]!!)})).jsonObject
                overwriteToken=approved["id"]!!.jsonPrimitive.content
            }
            upload=async(Dispatchers.IO){runCatching{transfer.upload("interrupted.bin",size,sha,{input},overwriteToken=overwriteToken){taskId.set(it.id);if(it.bytesTransferred>=512*1024)sending.complete(Unit)}}}
            withTimeout(5000){sending.await()}
            withTimeout(5000){
                while(true){
                    val progress=Json.decodeFromString<TransferProgress>(runtime.request("tool","task.progress",buildJsonObject{put("id",taskId.get())}))
                    if(progress.bytesTransferred>0)break
                    delay(50)
                }
            }
            if(cancel){
                upload.cancel();withTimeout(10000){upload.join()};assertTrue(upload.isCancelled)
                assertTrue(runtime.connected.value)
            }else{
                relay.cut();gate.countDown()
                assertTrue(withTimeout(10000){upload.await()}.isFailure)
                withTimeout(5000){runtime.connected.first{!it}}
                relay.restore();runtime.connect("127.0.0.1",relay.port)
            }
            assertTrue(closed.get())
            val page=Json.decodeFromString<TargetFilesPage>(runtime.request("tool","files.list",buildJsonObject{put("root","inbox")}))
            val expected=if(overwrite)listOf("interrupted.bin")else emptyList()
            withTimeout(5000){
                while(target.inboxEntries().lineSequence().filter{it.isNotBlank()}.map{it.substringAfterLast('/')}.sorted().toList()!=expected)delay(50)
            }
            assertEquals(expected,page.entries.map{it.name})
            if(overwrite)assertEquals("original",runtime.downloadFile("interrupted.bin","inbox").second.toString(Charsets.UTF_8))

        }finally{
            gate.countDown();input.close();upload?.cancelAndJoin();transfer.close();runtime.close();relay?.close();target.close()
        }
    }
}
