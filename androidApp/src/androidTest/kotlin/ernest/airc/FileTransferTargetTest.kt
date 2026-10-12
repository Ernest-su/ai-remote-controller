package ernest.airc

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*

/** Real phone ContentResolver and target HTTP streams; system picker interaction is separate. */
class FileTransferTargetTest {
    @Test fun phoneContentUriStreamsToTargetAndBackWithMatchingDigest():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val host=args.getString("fileTargetHost");val port=args.getString("fileTargetPort")?.toIntOrNull()
        assumeTrue("Explicit independent file target required",host!=null&&port!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val resolver=context.contentResolver;val name="airc-transfer-${java.util.UUID.randomUUID()}.bin"
        fun create(label:String)=checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{
            put(MediaStore.MediaColumns.DISPLAY_NAME,label);put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }))
        val source=create(name);var saved:android.net.Uri?=null
        val runtime=RuntimeConnection();val transfer=RuntimeTransferClient(runtime)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val controller=FileTransferController(scope,runtime)
        var downloaded:File?=null
        val bytes=ByteArray(64*1024){it.toByte()};val size=17L*1024*1024
        fun ByteArray.hex()=joinToString(""){(it.toInt()and 255).toString(16).padStart(2,'0')}
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            checkNotNull(resolver.openOutputStream(source)).use{out->repeat((size/bytes.size).toInt()){out.write(bytes);digest.update(bytes)}}
            var sha=digest.digest().hex()
            val status=runtime.connect(host!!,port!!);assertTrue(status.tools.contains("files.download"))
            assertEquals(host,runtime.session.value?.host)
            assertEquals("completed",withTimeout(60000){transfer.upload(name,size,sha,{checkNotNull(resolver.openInputStream(source))})}.phase)
            // Change actual URI bytes so a no-op or skipped overwrite cannot pass.
            bytes.fill(73)
            val replacement=MessageDigest.getInstance("SHA-256")
            checkNotNull(resolver.openOutputStream(source,"wt")).use{out->repeat((size/bytes.size).toInt()){out.write(bytes);replacement.update(bytes)}}
            sha=replacement.digest().hex()
            controller.select(UploadSource(name,size){checkNotNull(resolver.openInputStream(source))})
            controller.upload("inbox",name,true,overwrite=true)
            val pending=withTimeout(60000){controller.state.first{it.proposal!=null||!it.running}}
            assertEquals("awaiting_approval",pending.phase)
            assertEquals(name,pending.proposal?.path)
            assertEquals("overwrite",pending.proposal?.kind)
            assertEquals(size,pending.bytes)
            controller.confirm(true)
            val finished=withTimeout(60000){controller.state.first{!it.running}}
            assertEquals(finished.error,"completed",finished.phase)
            val received=withTimeout(60000){transfer.download(name,context.cacheDir,"inbox")};downloaded=received.second
            assertEquals(size,received.second.length())
            saved=create("saved-$name")
            checkNotNull(resolver.openOutputStream(saved!!)).use{out->received.second.inputStream().use{it.copyTo(out,64*1024)}}
            val actual=MessageDigest.getInstance("SHA-256");var count=0L
            checkNotNull(resolver.openInputStream(saved!!)).use{input->while(true){val n=input.read(bytes);if(n<0)break;count+=n;actual.update(bytes,0,n)}}
            assertEquals(size,count);assertEquals(sha,actual.digest().hex())
            val stat=Json.parseToJsonElement(runtime.request("tool","files.stat",buildJsonObject{put("root","inbox");put("path",name)})).jsonObject
            assertEquals(size,stat["sizeBytes"]!!.jsonPrimitive.long)
        }finally{
            controller.close();scope.cancel();transfer.close();runtime.close();downloaded?.delete()
            try{resolver.delete(source,null,null)}finally{saved?.let{resolver.delete(it,null,null)}}
        }
    }
}
