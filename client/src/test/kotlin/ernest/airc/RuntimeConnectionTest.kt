package ernest.airc

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RuntimeConnectionTest {
    @Test fun capturedToolWriterRejectsReconnectBeforeSendingToNewTarget()=runBlocking {
        val old=MockWebServer();val next=MockWebServer();val runtime=RuntimeConnection()
        val received=CopyOnWriteArrayList<String>()
        for(server in listOf(old,next)) {
            server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(socket:WebSocket,text:String){
                received+=text
                val request=Json.parseToJsonElement(text).jsonObject
                socket.send("""{"id":${request["id"]},"protocol":1,"ok":true,"result":"sent"}""")
            }}));server.start()
        }
        try {
            runtime.connect(old.hostName,old.port)
            val send=runtime.toolWriter()
            val text=runtime.textWriter()
            runtime.connect(next.hostName,next.port)
            for(tool in listOf("apps.open","remote.key","remote.swipe","device.info")) {
                val failure=runCatching {send(tool,kotlinx.serialization.json.buildJsonObject{})}.exceptionOrNull()
                assertNotNull(failure);assertTrue(received.isEmpty())
            }
            assertNotNull(runCatching{text("old target task",null)}.exceptionOrNull())
            assertTrue(received.isEmpty())
            assertEquals("sent",runtime.toolWriter()("apps.list",kotlinx.serialization.json.buildJsonObject{}))
            assertEquals(1,received.size)
        }finally{runtime.close();old.shutdown();next.shutdown()}
    }

    @Test fun olderWebSocketOpenTimeoutCannotDisconnectNewPeer() = runBlocking {
        val old=MockWebServer();val next=MockWebServer();val runtime=RuntimeConnection()
        old.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"old","tools":[]}"""))
        // Exceed the 10s client open deadline without exhausting MockWebServer's
        // 5s shutdown deadline after the assertion finishes.
        old.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){}).setHeadersDelay(12,TimeUnit.SECONDS));old.start()
        next.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"next","tools":[]}"""))
        next.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){}));next.start()
        val connecting=async {runCatching{runtime.connect(old.hostName,old.port)}}
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
                assertEquals("/status",old.takeRequest(3,TimeUnit.SECONDS)?.path)
                assertEquals("/ws",old.takeRequest(3,TimeUnit.SECONDS)?.path)
            }
            runtime.connect(next.hostName,next.port)
            val failure=withTimeout(12000){connecting.await()}.exceptionOrNull()
            assertTrue("Expected older open wait to time out: $failure",failure is kotlinx.coroutines.TimeoutCancellationException)
            assertTrue(runtime.connected.value);assertEquals("next",runtime.session.value?.deviceId)
            assertEquals(next.port,runtime.transferTarget().port)
        }finally{connecting.cancelAndJoin();runtime.close();old.shutdown();next.shutdown()}
    }
    @Test fun lateStatusFromOlderConnectCannotReplaceNewPeer() = runBlocking {
        val old=MockWebServer();val next=MockWebServer();val runtime=RuntimeConnection()
        val entered=kotlinx.coroutines.CompletableDeferred<Unit>();val release=java.util.concurrent.CountDownLatch(1)
        old.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
            entered.complete(Unit);release.await()
            return MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"old","tools":[]}""")
        }};old.start()
        next.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"next","tools":[]}"""))
        next.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){}));next.start()
        val connecting=async {runCatching{runtime.connect(old.hostName,old.port)}}
        try {
            withTimeout(3000){entered.await()}
            runtime.connect(next.hostName,next.port)
            release.countDown()
            assertTrue(withTimeout(3000){connecting.await()}.isFailure)
            assertTrue(runtime.connected.value);assertEquals("next",runtime.session.value?.deviceId)
            assertEquals(next.port,runtime.transferTarget().port)
        }finally{release.countDown();connecting.cancelAndJoin();runtime.close();old.shutdown();next.shutdown()}
    }
    @Test fun transferTargetMatchesPublishedSessionAndRejectsDifferentEndpoint() = runBlocking {
        val server=MockWebServer();val runtime=RuntimeConnection()
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){}));server.start()
        try {
            runtime.connect(server.hostName,server.port)
            val target=runtime.transferTarget();val session=checkNotNull(runtime.session.value)
            assertEquals(session.host,target.host);assertEquals(session.port,target.port);assertEquals(session.generation,target.generation)
            runtime.checkTransferTarget(target)
            try {runtime.checkTransferTarget(target.copy(host="different.invalid"));fail("Different endpoint accepted")}catch(expected:IllegalStateException){assertEquals("目标连接已改变",expected.message)}
            runtime.disconnect()
            try {runtime.checkTransferTarget(target);fail("Disconnected target accepted")}catch(expected:IllegalStateException){assertEquals("目标连接已改变",expected.message)}
        }finally{runtime.close();server.shutdown()}
    }
    @Test fun progressFramesDoNotCompleteThePendingRequestAndAreScopedById() = runBlocking {
        val server = MockWebServer()
        val runtime = RuntimeConnection()
        val phases = CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = Json.parseToJsonElement(text).jsonObject
                assertEquals("true", message.getValue("progress").jsonPrimitive.content)
                val id = message.getValue("id")
                webSocket.send("""{"id":"another-request","protocol":1,"type":"assistant.state","phase":"tool"}""")
                for (phase in listOf("thinking", "tool")) {
                    webSocket.send("""{"id":$id,"protocol":1,"type":"assistant.state","phase":"$phase","text":""}""")
                }
                webSocket.send("""{"id":$id,"protocol":1,"ok":true,"result":"completed"}""")
            }
        }))
        server.start()
        try {
            runtime.connect("localhost", server.port)
            val result = withTimeout(5_000) { runtime.request("text", text = "测试指令", onProgress = { phases.add(it.phase) }) }
            assertEquals("completed", result)
            assertEquals(listOf("thinking", "tool"), phases.toList())
        } finally { runtime.close(); server.shutdown() }
    }

    @Test fun targetBoundRequestsRejectOldGenerationAfterReconnect(): Unit = runBlocking {
        val first = MockWebServer(); val second = MockWebServer(); val runtime = RuntimeConnection()
        val received = CopyOnWriteArrayList<String>()
        for ((index,server) in listOf(first, second).withIndex()) {
            server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture-$index","tools":[]}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    val id = Json.parseToJsonElement(text).jsonObject.getValue("id")
                    webSocket.send("""{"id":$id,"protocol":1,"ok":true,"result":"accepted"}""")
                }
            }))
            server.start()
        }
        try {
            runtime.connect("localhost", first.port)
            val old = runtime.transferTarget()
            val oldSession = requireNotNull(runtime.session.value)
            assertEquals("fixture-0",oldSession.deviceId)
            runtime.connect("localhost", second.port)
            val newSession = requireNotNull(runtime.session.value)
            assertEquals("fixture-1",newSession.deviceId)
            assertNotEquals(oldSession.generation,newSession.generation)
            assertEquals(second.port,newSession.port)
            kotlin.test.assertFailsWith<IllegalStateException> {
                runtime.requestAt(old, "gamepad.virtual_close", kotlinx.serialization.json.JsonObject(emptyMap()))
            }
            assertTrue(received.isEmpty())
            assertEquals("accepted", runtime.requestAt(runtime.transferTarget(), "gamepad.virtual_capabilities",
                kotlinx.serialization.json.JsonObject(emptyMap())))
            assertEquals(1, received.size)
            runtime.disconnect();assertNull(runtime.session.value)
        } finally { runtime.close(); first.shutdown(); second.shutdown() }
    }

    @Test fun cancelsBlockedStatusAndCanRetry() = runBlocking {
        val server = MockWebServer()
        val runtime = RuntimeConnection()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody("""{"version":"0.1.0","protocol":1,"deviceId":"retry","tools":[]}"""))
        server.start()
        try {
            val pending = async { runtime.status("localhost", server.port) }
            // Wait for the actual request to enter the blocked HTTP operation.
            assertNotNull(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                server.takeRequest(2, TimeUnit.SECONDS)
            })
            withTimeout(2_000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
            assertEquals("retry", withTimeout(2_000) { runtime.status("localhost", server.port) }.deviceId)
        } finally { runtime.close(); server.shutdown() }
    }
}
