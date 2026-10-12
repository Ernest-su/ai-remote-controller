package ernest.airc.desktop

import ernest.airc.*
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class LanRuntimeTargetTest {
    @Test fun directLanStreamsFilesAndDownloadsScreenshotWithoutAdbDataForward():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial");assumeTrue("Set -PaircTargetSerial",!serial.isNullOrBlank())
        val host=DesktopDiscovery.tcpAdbHost(serial!!);assumeTrue("TCP target address required",host!=null)
        val target=ShellRuntimeTarget(serial);val runtime=RuntimeConnection();val transfer=RuntimeTransferClient(runtime)
        val directory=Files.createTempDirectory("airc-lan-stream-").toFile();val source=java.io.File(directory,"source.bin")
        val buffer=ByteArray(64*1024){it.toByte()};val size=17L*1024*1024;val digest=MessageDigest.getInstance("SHA-256")
        source.outputStream().use{out->repeat((size/buffer.size).toInt()){out.write(buffer);digest.update(buffer)}}
        fun ByteArray.hex()=joinToString(""){(it.toInt()and 255).toString(16).padStart(2,'0')}
        val sha=digest.digest().hex()
        try {
            target.prepare()
            val status=runtime.connect(host!!,target.runtimePort!!)
            assertEquals(host,runtime.session.value?.host);assertEquals(target.runtimePort,runtime.session.value?.port)
            assertTrue("files.mkdir" in status.tools)
            assertEquals("completed",withTimeout(30000){transfer.upload("source.bin",size,sha,source::inputStream)}.phase)
            val received=withTimeout(30000){transfer.download("source.bin",directory,"inbox")}
            assertEquals(size,received.second.length())
            val actual=MessageDigest.getInstance("SHA-256")
            received.second.inputStream().use{input->while(true){val n=input.read(buffer);if(n<0)break;actual.update(buffer,0,n)}}
            assertEquals(sha,actual.digest().hex())
            runtime.request("tool","files.mkdir",buildJsonObject{put("root","inbox");put("path","created")})
            val png=withTimeout(10000){runtime.screenshot()}
            val image=javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png))
            assertNotNull(image);assertTrue(image.width>0&&image.height>0)
        }finally{transfer.close();runtime.close();try{target.close()}finally{directory.deleteRecursively()}}
    }
}
