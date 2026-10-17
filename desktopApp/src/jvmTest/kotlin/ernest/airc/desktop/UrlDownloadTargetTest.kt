package ernest.airc.desktop

import ernest.airc.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Only a UUID service directory and UUID download filename are created on the selected target. */
class UrlDownloadTargetTest {
    @Test fun targetDownloadsIntoDefaultStorageAndSelectedDirectoryWithoutOverwriting(): Unit = runBlocking {
        val serial = System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned URL download fixture", !serial.isNullOrBlank())
        val adb = HostAdb()
        val id = java.util.UUID.randomUUID().toString()
        val directory = "/data/local/tmp/airc-url-$id"
        val binary = "$directory/airc-runtime"
        val filename = "airc-url-$id.bin"
        val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = UrlDownloadController(scope, runtime)
        val server = MockWebServer(); server.start()
        var owned = false; var copied = false; var port: Int? = null; var reverse: Int? = null
        suspend fun shell(command: String) = adb.command("-s", serial!!, "shell", command)
        suspend fun awaitResult(): UrlDownloadState = withTimeout(10000) { controller.state.first { !it.busy } }
        try {
            val abi = shell("getprop ro.product.cpu.abi").trim()
            check(abi in listOf("armeabi-v7a", "arm64-v8a", "x86_64"))
            val asset = File(checkNotNull(System.getProperty("airc.runtimeAssets")), "airc-runtime-$abi"); check(asset.isFile)
            shell("mkdir -m 700 $directory"); owned = true
            adb.command("-s", serial!!, "push", asset.absolutePath, binary); copied = true
            shell("chmod 700 $binary")
            val service = Json.parseToJsonElement(shell("$binary service start --state-dir $directory/state --port 0 --no-mdns")).jsonObject
            assertTrue(service["ready"]!!.jsonPrimitive.boolean)
            port = adb.forward(serial, service["port"]!!.jsonPrimitive.int)
            runtime.connect("127.0.0.1", port)
            reverse = adb.command("-s", serial, "reverse", "tcp:0", "tcp:${server.port}").trim().toInt()
            val url = "http://127.0.0.1:$reverse/source.bin"
            server.enqueue(MockResponse().setBody("download fixture"))
            controller.editUrl(url); controller.editName(filename); controller.start(true)
            val default = awaitResult()
            assertNull(default.error); assertEquals("completed", default.phase)
            assertEquals("download fixture", shell("cat /storage/emulated/0/Download/$filename"))
            assertEquals(16L, default.bytes)
            server.enqueue(MockResponse().setBody("replacement"))
            controller.start(true)
            val duplicate = awaitResult()
            assertEquals("failed", duplicate.phase)
            assertEquals("download fixture", shell("cat /storage/emulated/0/Download/$filename"))
            runtime.request("tool", "files.mkdir", buildJsonObject { put("root", "inbox"); put("path", "chosen") })
            server.enqueue(MockResponse().setChunkedBody("selected directory", 3))
            controller.directory("inbox", "chosen"); controller.editName(""); controller.start(true)
            val selected = awaitResult()
            assertNull(selected.error); assertEquals("completed", selected.phase)
            assertEquals("selected directory", shell("cat $directory/state/files/chosen/source.bin"))
            assertEquals("服务收件箱/chosen/source.bin", selected.destination)
        } finally {
            controller.clear(); scope.cancel(); runtime.close(); server.shutdown()
            try { if(copied) shell("$binary service stop --state-dir $directory/state") }
            finally {
                reverse?.let { adb.command("-s", serial!!, "reverse", "--remove", "tcp:$it") }
                port?.let { adb.removeForward(serial!!, it) }
                shell("rm -f -- /storage/emulated/0/Download/$filename")
                if(owned) shell("rm -rf -- $directory")
            }
        }
    }
}
