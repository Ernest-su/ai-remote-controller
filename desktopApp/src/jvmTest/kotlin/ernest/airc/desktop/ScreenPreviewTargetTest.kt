package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.*

/** Historical binary protocol acceptance only; JVM product has no dynamic preview. */
class ScreenPreviewTargetTest {
    @Test fun realAnimatedFramesExpireAndControllerStopDisconnectReleaseCapture(): Unit = runBlocking {
        val serial = System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned animated screen fixture",!serial.isNullOrBlank())
        val adb = HostAdb(); val pkg = "ernest.airc.mediafixture"
        check(adb.command("-s",serial!!,"shell","pm list packages --user all $pkg").lines().none { it == "package:$pkg" }) { "Refusing to replace preexisting fixture" }
        val apk = File(checkNotNull(System.getProperty("airc.mediaFixtureApk"))); assertTrue(apk.isFile)
        val target = ShellRuntimeTarget(serial,root=true); val runtime = RuntimeConnection(); val client = RuntimePreviewClient()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default); val controller = ScreenPreviewController(scope,runtime)
        val json = Json { ignoreUnknownKeys = true }; var installed = false; var lease: String? = null
        suspend fun prepare(): ScreenPreviewDescriptor = json.decodeFromString(runtime.request("tool","screen.preview_start",buildJsonObject { put("intervalMs",500); put("durationSeconds",10) }))
        try {
            target.prepare()
            val port=checkNotNull(target.port)
            check(adb.command("-s",serial,"install",apk.absolutePath).contains("Success")); installed = true
            adb.command("-s",serial,"shell","am start -W -n $pkg/.ColorActivity"); delay(500)
            runtime.connect("127.0.0.1",port)
            val descriptor = prepare(); lease = descriptor.id
            assertFails { prepare() }
            var count = 0L; val digests = mutableSetOf<List<Byte>>()
            withTimeout(25000) { client.read("127.0.0.1",port,descriptor) { frame ->
                assertEquals(++count,frame.sequence)
                val image = checkNotNull(ImageIO.read(ByteArrayInputStream(frame.png)))
                assertEquals(frame.width,image.width); assertEquals(frame.height,image.height)
                var matches = 0; var samples = 0
                for(y in 0 until image.height step 24) for(x in 0 until image.width step 24) {
                    val c = image.getRGB(x,y); val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
                    if(b>190 && ((r<60 && g>190)||(r>190 && g<60))) matches++; samples++
                }
                assertTrue(matches>samples/2,"Frames must display our fixture colors")
                digests += MessageDigest.getInstance("SHA-256").digest(frame.png).toList()
            } }
            lease = null; assertTrue(count >= 3); assertTrue(digests.size >= 2,"Animated pixels must change across frames")
            assertTrue(controller.start(500,true))
            withTimeout(15000) { controller.state.first { it.frame != null } }
            controller.stop(); withTimeout(7000) { controller.state.first { !it.running && !it.stopping } }
            controller.clear(); assertNull(controller.state.value.frame)
            assertTrue(runtime.screenshot().isNotEmpty(),"Capture lock must be released after stop")
            assertTrue(controller.start(500,true)); withTimeout(15000) { controller.state.first { it.frame != null } }
            runtime.disconnect(); controller.clear()
            withTimeout(7000) { controller.state.first { !it.stopping } }
            assertNull(controller.state.value.frame); assertFalse(controller.state.value.running)
            runtime.connect("127.0.0.1",port)
            withTimeout(5000) {
                while(true) {
                    val next = runCatching { prepare() }.getOrNull()
                    if(next != null) { lease = next.id; break }
                    delay(100)
                }
            }
            runtime.request("tool","screen.preview_stop",buildJsonObject { put("id",checkNotNull(lease)) }); lease = null
        } finally {
            controller.clear(); scope.cancel(); client.close()
            lease?.let { id -> runCatching { runtime.request("tool","screen.preview_stop",buildJsonObject { put("id",id) }) } }
            runtime.close()
            try { if(installed) check(adb.command("-s",serial,"uninstall",pkg).contains("Success")) }
            finally { target.close() }
        }
    }
}
