package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class LargeTransferTargetTest {
    @Test fun boundedChunksTransfer256MiBAndVerifyIndependentDigest(): Unit = runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Explicit target required for 256 MiB acceptance",!serial.isNullOrBlank())
        val target=ShellRuntimeTarget(serial!!)
        val runtime=RuntimeConnection()
        val transfer=RuntimeTransferClient(runtime)
        val cache=Files.createTempDirectory("airc-large-transfer-").toFile()
        val size=256L*1024*1024
        val chunk=ByteArray(64*1024){(it%251).toByte()}
        val digest=MessageDigest.getInstance("SHA-256")
        repeat((size/chunk.size).toInt()){digest.update(chunk)}
        fun ByteArray.hex()=joinToString(""){(it.toInt()and 255).toString(16).padStart(2,'0')}
        val expected=digest.digest().hex()
        var uploaded=0L;var downloaded=0L
        try {
            target.prepare()
            runtime.connect("127.0.0.1",checkNotNull(target.port))
            val result=withTimeout(180000){transfer.upload("large.bin",size,expected,{
                object:InputStream(){
                    var position=0L
                    override fun read():Int=error("Bulk reads required")
                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                        assertTrue(length<=chunk.size,"Upload buffer exceeds 64 KiB")
                        if(position==size)return -1
                        val count=minOf(length.toLong(),size-position).toInt()
                        for(index in 0 until count)bytes[offset+index]=chunk[((position+index)%chunk.size).toInt()]
                        position+=count
                        return count
                    }
                }
            }){uploaded=maxOf(uploaded,it.bytesTransferred)}}
            assertEquals("completed",result.phase);assertEquals(size,uploaded)
            val file=withTimeout(180000){transfer.download("large.bin",cache,"inbox"){downloaded=maxOf(downloaded,it.bytesTransferred)}}.second
            assertEquals(size,downloaded);assertEquals(size,file.length())
            val actual=MessageDigest.getInstance("SHA-256")
            file.inputStream().use{input->while(true){val count=input.read(chunk);if(count<0)break;actual.update(chunk,0,count)}}
            assertEquals(expected,actual.digest().hex())
        } finally {
            transfer.close();runtime.close()
            try{target.close()}finally{cache.deleteRecursively()}
        }
    }
}
