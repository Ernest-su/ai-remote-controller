package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.net.InetSocketAddress
import kotlin.test.*

class ScreenMirrorBackpressureTargetTest {
    @Test fun blockedAndroidEncoderReleasesWhileTcpReaderRemainsOpen() = verify(false)
    @Test fun ownerDisconnectReleasesBlockedEncoderWithoutClosingHttpReader() = verify(true)
    private fun verify(disconnect:Boolean):Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned real encoder backpressure",!serial.isNullOrBlank())
        val target=VirtualGamepadTarget(serial!!);val runtime=RuntimeConnection();val peer=Socket()
        try {
            target.prepare(".NoiseActivity")
            val port=checkNotNull(target.port);runtime.connect("127.0.0.1",port)
            val ticket=Json.decodeFromString<ScreenMirrorDescriptor>(runtime.request("tool","screen.mirror_start",buildJsonObject{put("bitrate",8000000);put("durationSeconds",60)}))
            withContext(Dispatchers.IO){
                peer.receiveBufferSize=1024;peer.soTimeout=5000;peer.connect(InetSocketAddress("127.0.0.1",port))
                peer.getOutputStream().write("GET ${ticket.path} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                val header=StringBuilder()
                while(!header.endsWith("\r\n\r\n")){
                    val value=peer.getInputStream().read();check(value>=0);header.append(value.toChar());check(header.length<=16384)
                }
                assertTrue(header.startsWith("HTTP/1.1 200"),header.toString())
            }
            var encoder:Int?=null;var wait=""
            withTimeout(25000){while(true){
                encoder=target.encoderPid()
                if(encoder!=null){wait=target.encoderWait(encoder!!);if(wait.endsWith("pipe_write")){
                    delay(350)
                    if(target.encoderPid()==encoder&&target.encoderWait(encoder!!).endsWith("pipe_write"))break
                }}
                delay(100)
            }}
            assertNotNull(encoder,"No owned encoder observed")
            assertTrue(wait.endsWith("pipe_write"),wait)
            // Do not read or close TCP: cancellation must release encoder resources independently.
            if(disconnect) runtime.disconnect()
            else runtime.request("tool","screen.mirror_stop",buildJsonObject{put("id",ticket.id)})
            withTimeout(5000){while(target.encoderPid()!=null)delay(50)}
            assertFalse(peer.isClosed)
            if(disconnect) runtime.connect("127.0.0.1",port)
            val replacement=withTimeout(5000){var result:ScreenMirrorDescriptor?=null
                while(result==null){result=runCatching{Json.decodeFromString<ScreenMirrorDescriptor>(runtime.request("tool","screen.mirror_start"))}.getOrNull();if(result==null)delay(50)}
                result
            }
            runtime.request("tool","screen.mirror_stop",buildJsonObject{put("id",replacement.id)})
            assertTrue(withTimeout(5000){runtime.screenshot()}.isNotEmpty())
            peer.close()
            if(!disconnect) {
            // A separate stalled reader must expire even with its owner still connected.
            val expiring=Json.decodeFromString<ScreenMirrorDescriptor>(runtime.request("tool","screen.mirror_start",buildJsonObject{put("bitrate",8000000);put("durationSeconds",10)}))
            Socket().use { stalled ->
                withContext(Dispatchers.IO){
                    stalled.receiveBufferSize=1024;stalled.soTimeout=5000;stalled.connect(InetSocketAddress("127.0.0.1",port))
                    stalled.getOutputStream().write("GET ${expiring.path} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                    val header=StringBuilder()
                    while(!header.endsWith("\r\n\r\n")){val value=stalled.getInputStream().read();check(value>=0);header.append(value.toChar());check(header.length<=16384)}
                    assertTrue(header.startsWith("HTTP/1.1 200"))
                }
                withTimeout(8000){while(target.encoderPid()==null)delay(50)}
                withTimeout(18000){while(target.encoderPid()!=null)delay(50)}
                assertFalse(stalled.isClosed)
                val screenshot=withTimeout(7000){var bytes:ByteArray?=null
                    while(bytes==null){bytes=runCatching{runtime.screenshot()}.getOrNull();if(bytes==null)delay(50)}
                    bytes
                }
                assertTrue(screenshot.isNotEmpty())
            }
            }
        } finally {peer.close();runtime.close();target.close()}
    }
}
