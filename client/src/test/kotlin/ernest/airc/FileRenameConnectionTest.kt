package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class FileRenameConnectionTest {
    @Test fun staleDirectoryCannotRenameAfterSamePeerReconnect(): Unit = runBlocking {
        val renames = AtomicInteger()
        val lists = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/status") MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"same-peer","tools":[]}""")
            else MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(socket: WebSocket, text: String) {
                    val message = Json.parseToJsonElement(text).jsonObject
                    val tool = message.getValue("tool").jsonPrimitive.content
                    if (tool == "files.list") lists.incrementAndGet()
                    if (tool == "files.rename") renames.incrementAndGet()
                    val result = if (tool == "files.list") """{"root":"downloads","path":"","entries":[{"name":"old.txt","kind":"file"}],"nextCursor":"old.txt"}""" else "{}"
                    socket.send(buildJsonObject { put("id", message.getValue("id")); put("protocol",1); put("ok",true); put("result",result) }.toString())
                }
            })
        }
        server.start()
        val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val browser = FileBrowserController(scope, runtime)
        try {
            runtime.connect(server.hostName,server.port)
            browser.refresh(true)
            val files = withTimeout(3_000) { browser.state.first { it.loaded } }
            runtime.connect(server.hostName,server.port)
            browser.more(true)
            withTimeout(3_000) { browser.state.first { !it.loading && it.error != null } }
            assertEquals(1, lists.get())
            browser.rename(files.entries.single(), "new.txt", true)
            assertEquals("目标连接已改变", browser.state.value.error)
            assertEquals(0,renames.get())
        } finally { browser.clear(); runtime.close(); scope.cancel(); server.shutdown() }
    }
}
