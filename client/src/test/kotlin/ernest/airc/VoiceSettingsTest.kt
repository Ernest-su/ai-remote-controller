package ernest.airc

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import kotlin.test.*

class VoiceSettingsTest {
    @Test fun validatesOptionalKeyAndPackageWithoutShellSyntax() {
        assertEquals(1017, VoiceSettings().validate())
        assertEquals(0, VoiceSettings(keyCode = "").validate())
        assertEquals(65535, VoiceSettings(keyCode = "65535").validate())
        for (key in listOf("0", "-1", "65536", "1.5", "1017;reboot")) assertFailsWith<IllegalArgumentException> { VoiceSettings(keyCode = key).validate() }
        for (name in listOf("", "android", "com..a", "com.1a", "com.a;reboot", "com.a\n")) assertFailsWith<IllegalArgumentException> { VoiceSettings(packageName = name).validate() }
    }
    @Test fun streamsPcmOnlyAfterReadinessAndWaitsForCleanupAcknowledgement() = runBlocking {
        val server = MockWebServer()
        val settings = CompletableDeferred<String>()
        val audio = CompletableDeferred<ByteString>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                if (text == "stop") socket.send("{\"stopped\":true}")
                else { settings.complete(text); socket.send("{\"ready\":true,\"sampleRate\":16000,\"channels\":1}") }
            }
            override fun onMessage(socket: WebSocket, bytes: ByteString) { audio.complete(bytes) }
        }))
        server.start()
        val session = RuntimeSession("fixture", "127.0.0.1", server.port, 1)
        val stream = RuntimeVoiceStream(session) { session }
        try {
            stream.open(VoiceSettings())
            assertTrue(withTimeout(2000) { settings.await() }.contains("1017"))
            val pcm = ByteArray(640) { (it % 251).toByte() }
            stream.send(pcm, pcm.size)
            assertContentEquals(pcm, withTimeout(2000) { audio.await() }.toByteArray())
            stream.stop()
            assertEquals("/voice", server.takeRequest().path)
        } finally { stream.close(); server.shutdown() }
    }
    @Test fun targetChangePreventsAudioFromReachingPreviousSession() = runBlocking {
        val server = MockWebServer()
        val audio = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) { socket.send("{\"ready\":true}") }
            override fun onMessage(socket: WebSocket, bytes: ByteString) { audio.complete(Unit) }
        }))
        server.start()
        val session = RuntimeSession("fixture", "127.0.0.1", server.port, 1)
        var current: RuntimeSession? = session
        val stream = RuntimeVoiceStream(session) { current }
        try {
            stream.open(VoiceSettings())
            current = session.copy(generation = 2)
            assertFailsWith<IllegalStateException> { stream.send(ByteArray(640), 640) }
            assertFalse(audio.isCompleted)
        } finally { stream.close(); server.shutdown() }
    }
    @Test fun cancelledHandshakeStillSendsStopAndWaitsForRemoteCleanup() = runBlocking {
        val server = MockWebServer()
        val received = CompletableDeferred<Unit>()
        val stopReceived = CompletableDeferred<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "stop") stopReceived.complete(webSocket) else received.complete(Unit)
            }
        }))
        server.start()
        val session = RuntimeSession("fixture", "127.0.0.1", server.port, 1)
        val stream = RuntimeVoiceStream(session) { session }
        try {
            val opening = launch { stream.open(VoiceSettings()) }
            withTimeout(2000) { received.await() }
            opening.cancelAndJoin()
            val cleanup = async { stream.stop() }
            val remote = withTimeout(2000) { stopReceived.await() }
            assertFalse(cleanup.isCompleted)
            remote.send("{\"stopped\":true}")
            withTimeout(2000) { cleanup.await() }
        } finally { stream.close(); server.shutdown() }
    }
    @Test fun routingFailureIsReportedBeforeRecordingIsReady() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) { socket.send("{\"error\":\"routing denied\"}") }
        }))
        server.start()
        val session = RuntimeSession("fixture", "127.0.0.1", server.port, 1)
        val stream = RuntimeVoiceStream(session) { session }
        try { assertEquals("routing denied", assertFailsWith<IllegalStateException> { stream.open(VoiceSettings()) }.message) }
        finally { stream.close(); server.shutdown() }
    }
}
