package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.*

class UrlMetadataProtocolTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun realRuntimeProbesRedirectHeadersThenDownloadsWithDetectedUnicodeName() = runBlocking {
        val binary = File(requireNotNull(System.getProperty("airc.hostRuntime")))
        val port = ServerSocket(0).use { it.localPort }
        val state = temporary.newFolder("state")
        val log = temporary.newFile("runtime.log")
        val process = ProcessBuilder(binary.path, "serve", "--no-mdns", "--state-dir", state.path, "--port", port.toString()).redirectErrorStream(true).redirectOutput(log).start()
        val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = UrlDownloadController(scope, runtime)
        val server = MockWebServer().apply { start() }
        try {
            withTimeout(10000) { while (runCatching { runtime.status("127.0.0.1", port) }.isFailure) { check(process.isAlive) { log.readText() }; delay(50) } }
            assertTrue("files.url_info" in runtime.connect("127.0.0.1", port).tools)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/asset")))
            server.enqueue(MockResponse().setHeader("Content-Length", 4).setHeader("Content-Disposition", "attachment; filename*=UTF-8''%E7%94%B5%E8%A7%86.apk"))
            controller.editUrl(server.url("/opaque").toString()); controller.inspect(true)
            val info = withTimeout(5000) { controller.state.first { it.infoReady || it.infoError != null } }
            assertNull(info.infoError); assertEquals("电视.apk", info.name); assertEquals(4L, info.fileSize)
            assertFalse(info.busy); assertEquals("idle", info.phase)
            assertEquals("HEAD", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            assertEquals("HEAD", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            assertFalse(File(state, "files/电视.apk").exists())
            server.enqueue(MockResponse().setBody("data"))
            controller.directory("inbox", ""); controller.start(true)
            val done = withTimeout(5000) { controller.state.first { !it.busy } }
            assertNull(done.error); assertEquals("completed", done.phase)
            assertEquals("data", File(state, "files/电视.apk").readText())
            assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            // Existing destination is reported before making another HTTP request.
            controller.start(true)
            val collision = withTimeout(5000) { controller.state.first { !it.busy } }
            assertNull(collision.error); assertEquals("电视 (1).apk", collision.conflict?.suggestedName)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("new copy"))
            controller.renameConflict("电视 (1).apk", true)
            assertNull(withTimeout(5000) { controller.state.first { !it.busy } }.error)
            assertEquals("data", File(state, "files/电视.apk").readText())
            assertEquals("new copy", File(state, "files/电视 (1).apk").readText())
            assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            controller.editName("电视.apk"); controller.start(true)
            withTimeout(5000) { controller.state.first { !it.busy && it.conflict != null } }
            controller.start(true, overwrite = true)
            withTimeout(5000) { controller.state.first { it.overwriteProposal != null } }
            controller.confirmOverwrite(false)
            withTimeout(5000) { controller.state.first { !it.busy } }
            assertEquals("data", File(state, "files/电视.apk").readText())
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            controller.start(true)
            withTimeout(5000) { controller.state.first { !it.busy && it.conflict != null } }
            controller.start(true, overwrite = true)
            withTimeout(5000) { controller.state.first { it.overwriteProposal != null } }
            assertEquals("data", File(state, "files/电视.apk").readText())
            server.enqueue(MockResponse().setBody("replacement"))
            controller.confirmOverwrite(true)
            val replaced = withTimeout(5000) { controller.state.first { !it.busy } }
            assertNull(replaced.error); assertEquals("completed", replaced.phase)
            assertEquals("replacement", File(state, "files/电视.apk").readText())
            assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            controller.start(true)
            withTimeout(5000) { controller.state.first { !it.busy && it.conflict != null } }
            controller.start(true, overwrite = true)
            withTimeout(5000) { controller.state.first { it.overwriteProposal != null } }
            server.enqueue(MockResponse().setResponseCode(404))
            controller.confirmOverwrite(true)
            assertNotNull(withTimeout(5000) { controller.state.first { !it.busy } }.error)
            assertEquals("replacement", File(state, "files/电视.apk").readText())
            assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)!!.method)


        } finally {
            controller.clear(); scope.cancel(); runtime.close(); server.shutdown(); process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }
}
