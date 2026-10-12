package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.Buffer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

class AppIconControllerTest {
    @Test fun scrollingCancellationKeepsSentRequestsWithinTheLimit(): Unit = runBlocking {
        val server = MockWebServer()
        val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = AppIconController(scope, runtime)
        val received = kotlinx.coroutines.channels.Channel<Pair<WebSocket, JsonObject>>(64)
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"slow-icons","tools":["apps.icon"]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                received.trySend(socket to Json.parseToJsonElement(text).jsonObject)
            }
        }))
        server.start()
        val jobs = mutableListOf<Job>()
        try {
            runtime.connect(server.hostName, server.port)
            // Two actual remote requests remain unfinished while their rows leave composition.
            repeat(2) { index ->
                jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { controller.load("test.app$index") }
                }
            }
            val first = withTimeout(3000) { received.receive() }
            val second = withTimeout(3000) { received.receive() }
            jobs.forEach { it.cancel() }
            // More than the runtime's 16-slot queue: canceled, unseen rows must never be sent.
            repeat(40) { index ->
                val row = launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { controller.load("test.scrolled$index") }
                }
                jobs += row
                row.cancel()
            }
            val visible = async { runCatching { controller.load("test.visible") } }
            assertNull(withTimeoutOrNull(300) { received.receive() }, "Canceled rows released permits before remote replies")
            // Refresh invalidates old work without allowing it to bypass the same limit.
            controller.clear()
            for ((socket, request) in listOf(first, second)) {
                socket.send(buildJsonObject {
                    put("id", request.getValue("id")); put("protocol", 1)
                    put("ok", false); put("error", "fixture icon unavailable")
                }.toString())
            }
            withTimeout(3000) { jobs.joinAll() }
            // The queued load belongs to the pre-refresh revision and must not be sent.
            assertTrue(withTimeout(3000) { visible.await() }.isFailure)
            assertNull(withTimeoutOrNull(100) { received.receive() })
            val fresh = async { runCatching { controller.load("test.fresh") } }
            val (socket, request) = withTimeout(3000) { received.receive() }
            assertEquals("test.fresh", request.getValue("arguments").jsonObject.getValue("package").jsonPrimitive.content)
            socket.send(buildJsonObject {
                put("id", request.getValue("id")); put("protocol", 1)
                put("ok", false); put("error", "fixture icon unavailable")
            }.toString())
            assertTrue(withTimeout(3000) { fresh.await() }.isFailure)
            assertTrue(runtime.connected.value)
        } finally {
            runtime.close()
            jobs.forEach { it.cancel() }
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun canceledDescriptorWaitDoesNotDownloadOrCacheAnOffscreenIcon(): Unit = runBlocking {
        IconFixture().use { fixture ->
            val offscreen = async(start = CoroutineStart.UNDISPATCHED) { fixture.controller.load("test.app") }
            val request = fixture.nextRequest()
            offscreen.cancel()
            fixture.reply(request)
            withTimeout(3000) { offscreen.join() }
            assertTrue(offscreen.isCancelled)
            assertEquals(0, fixture.downloads.get(), "An offscreen icon started a download")

            // The canceled result must not populate the cache: returning to this row
            // requires a new descriptor and exactly one actual image download.
            val visible = async { fixture.controller.load("test.app") }
            fixture.reply(fixture.nextRequest())
            assertContentEquals(fixture.png, withTimeout(3000) { visible.await() })
            assertEquals(1, fixture.downloads.get())
            assertTrue(fixture.runtime.connected.value)
        }
    }

    @Test fun cancelingBlockedIconDownloadFreesItsPermitWithoutWaitingForHttpTimeout(): Unit = runBlocking {
        IconFixture(blockDownloads = true).use { fixture ->
            val first = async { fixture.controller.load("test.first") }
            val second = async { fixture.controller.load("test.second") }
            fixture.reply(fixture.nextRequest())
            fixture.reply(fixture.nextRequest())
            repeat(2) { withTimeout(3000) { fixture.downloadStarted.receive() } }
            val next = async { runCatching { fixture.controller.load("test.next") } }
            assertNull(withTimeoutOrNull(150) { fixture.requests.receive() })

            withTimeout(3000) { first.cancelAndJoin() }
            val request = fixture.nextRequest()
            assertEquals("test.next", request.second.getValue("arguments").jsonObject.getValue("package").jsonPrimitive.content)
            fixture.reject(request)
            assertTrue(withTimeout(3000) { next.await() }.isFailure)
            withTimeout(3000) { second.cancelAndJoin() }
            assertEquals(2, fixture.downloads.get())
            assertTrue(fixture.runtime.connected.value)
        }
    }

    @Test fun cacheIsConnectionScopedRefreshableAndRejectsWrongPackage(): Unit = runBlocking {
        val server=MockWebServer();val runtime=RuntimeConnection()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val controller=AppIconController(scope,runtime)
        val calls=AtomicInteger();var wrong=false
        val png=ByteArrayOutputStream().also { ImageIO.write(BufferedImage(96,96,BufferedImage.TYPE_INT_ARGB),"png",it) }.toByteArray()
        val id="12345678-1234-1234-1234-123456789abc"
        server.dispatcher=object: okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse = when(request.path) {
                "/status"->MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"same","tools":["apps.icon"]}""")
                "/ws"->MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(socket:WebSocket,text:String){
                    calls.incrementAndGet()
                    val request=Json.parseToJsonElement(text).jsonObject
                    val artifact=buildJsonObject {
                        put("id",id);put("path","/artifacts/$id");put("mimeType","image/png");put("sizeBytes",png.size);put("width",96);put("height",96);put("expiresInSeconds",60);put("package",if(wrong)"wrong.app" else "test.app")
                    }
                    socket.send(buildJsonObject { put("id",request.getValue("id"));put("protocol",1);put("ok",true);put("result",artifact.toString()) }.toString())
                }})
                "/artifacts/$id"->MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(png))
                else->MockResponse().setResponseCode(404)
            }
        };server.start()
        try {
            runtime.connect(server.hostName,server.port);yield()
            assertContentEquals(png,controller.load("test.app"));assertContentEquals(png,controller.load("test.app"));assertEquals(1,calls.get())
            controller.clear();assertContentEquals(png,controller.load("test.app"));assertEquals(2,calls.get())
            runtime.connect(server.hostName,server.port);yield()
            assertContentEquals(png,controller.load("test.app"));assertEquals(3,calls.get())
            controller.clear();wrong=true
            assertFails { controller.load("test.app") };assertEquals(4,calls.get())
        } finally { scope.cancel();runtime.close();server.shutdown() }
    }

    private class IconFixture(private val blockDownloads: Boolean = false) : AutoCloseable {
        val server = MockWebServer()
        val runtime = RuntimeConnection()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = AppIconController(scope, runtime)
        val requests = kotlinx.coroutines.channels.Channel<Pair<WebSocket, JsonObject>>(16)
        val downloadStarted = kotlinx.coroutines.channels.Channel<Unit>(16)
        val downloads = AtomicInteger()
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        private val id = "12345678-1234-1234-1234-123456789abc"

        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/status" -> MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"icons","tools":["apps.icon"]}""")
                    "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            requests.trySend(webSocket to Json.parseToJsonElement(text).jsonObject)
                        }
                    })
                    "/artifacts/$id" -> {
                        downloads.incrementAndGet()
                        downloadStarted.trySend(Unit)
                        if (blockDownloads) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        else MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            runBlocking { runtime.connect(server.hostName, server.port) }
        }

        suspend fun nextRequest() = withTimeout(3000) { requests.receive() }

        fun reply(request: Pair<WebSocket, JsonObject>) {
            val artifact = buildJsonObject {
                put("id", id); put("path", "/artifacts/$id"); put("mimeType", "image/png")
                put("sizeBytes", png.size); put("width", 96); put("height", 96); put("expiresInSeconds", 60)
                put("package", request.second.getValue("arguments").jsonObject.getValue("package"))
            }
            request.first.send(buildJsonObject {
                put("id", request.second.getValue("id")); put("protocol", 1); put("ok", true)
                put("result", artifact.toString())
            }.toString())
        }

        fun reject(request: Pair<WebSocket, JsonObject>) {
            request.first.send(buildJsonObject {
                put("id", request.second.getValue("id")); put("protocol", 1); put("ok", false)
                put("error", "fixture icon unavailable")
            }.toString())
        }

        override fun close() {
            runtime.close()
            scope.cancel()
            server.shutdown()
        }
    }

}
