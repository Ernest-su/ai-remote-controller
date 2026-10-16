package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class RuntimeLanguageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sendsCurrentAppLanguageOnStatusAndWebSocketAndUpdatesAfterReconnect() = runBlocking {
        var appLanguage = "zh-Hans-CN"
        val runtime = RuntimeConnection { appLanguage }
        val server = MockWebServer()
        server.start()
        try {
            for ((tag, expected) in listOf("zh-Hans-CN" to "zh", "en-US" to "en", "fr-FR" to "en")) {
                appLanguage = tag
                server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
                runtime.connect(server.hostName, server.port)
                assertEquals(expected, server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Accept-Language"))
                assertEquals(expected, server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Accept-Language"))
                runtime.disconnect()
            }
        } finally { runtime.close(); server.shutdown() }
    }

    @Test fun realRuntimeKeepsSimultaneousClientLanguagesSeparate() = runBlocking {
        val binary = File(requireNotNull(System.getProperty("airc.hostRuntime")))
        val port = ServerSocket(0).use { it.localPort }
        val log = temporary.newFile("runtime.log")
        val process = ProcessBuilder(binary.path, "serve", "--no-mdns", "--state-dir", temporary.newFolder("state").path, "--port", port.toString())
            .redirectErrorStream(true).redirectOutput(log).start()
        val chinese = RuntimeConnection { "zh-CN" }
        val english = RuntimeConnection { "en-US" }
        suspend fun failure(runtime: RuntimeConnection): String {
            try { runtime.request("tool", "gamepad.virtual_update", buildJsonObject {}) }
            catch (e: IllegalStateException) { return e.message.orEmpty() }
            error("Expected an invalid request to fail")
        }
        try {
            withTimeout(10_000) {
                while (runCatching { english.status("127.0.0.1", port) }.isFailure) {
                    check(process.isAlive) { log.readText() }
                    delay(50)
                }
            }
            chinese.connect("127.0.0.1", port)
            english.connect("127.0.0.1", port)
            assertEquals("缺少操作会话 ID", failure(chinese))
            assertEquals("id required", failure(english))
            assertEquals("缺少操作会话 ID", failure(chinese))
            // This probe opens no virtual device and only reports target kernel/root availability.
            for ((runtime, zh) in listOf(chinese to true, english to false)) {
                val capability = Json.parseToJsonElement(runtime.request("tool", "gamepad.virtual_capabilities")).jsonObject
                if (capability["supported"]?.jsonPrimitive?.boolean == false) {
                    val reason = capability.getValue("reason").jsonPrimitive.content
                    assertEquals(zh, reason.any { it.code in 0x4E00..0x9FFF })
                }
            }
        } finally {
            chinese.close(); english.close()
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }
}
