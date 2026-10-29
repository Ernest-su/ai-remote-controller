package ernest.airc

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class VoiceSettingsTest {
    private class Peer(val autoReady: Boolean = true, val credit: Long = 128000, val autoCredit: Boolean = true, val reject: Boolean = false, val truncateEnd: Boolean = false) : AutoCloseable {
        val server = MockWebServer()
        val settings = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val bytes = AtomicLong()
        private val received = ByteArrayOutputStream()
        lateinit var socket: WebSocket
        val session get() = RuntimeSession("fixture", "127.0.0.1", server.port, 1)
        init {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (text) {
                        "start" -> {
                            started.complete(Unit)
                            if (reject) webSocket.send("{\"error\":\"routing denied\"}")
                            else if (autoReady) ready()
                        }
                        "cancel" -> { cancelled.complete(Unit); webSocket.send("{\"stopped\":true,\"cancelled\":true}") }
                        "end" -> webSocket.send("{\"stopped\":true,\"cancelled\":false,\"receivedBytes\":${bytes.get()},\"injectedBytes\":${bytes.get() - if (truncateEnd) 2 else 0}}")
                        else -> { settings.complete(Unit); webSocket.send("{\"prepared\":true,\"windowBytes\":$credit}") }
                    }
                }
                override fun onMessage(webSocket: WebSocket, data: ByteString) {
                    synchronized(received) { received.write(data.toByteArray()) }
                    val total = bytes.addAndGet(data.size.toLong())
                    if (autoCredit) webSocket.send("{\"consumedBytes\":$total}")
                }
            }))
            server.start()
        }
        fun ready() { socket.send("{\"ready\":true,\"sampleRate\":16000,\"channels\":1,\"receiverSession\":42}") }
        fun received() = synchronized(received) { received.toByteArray() }
        override fun close() { if (::socket.isInitialized) socket.close(1000, "fixture finished"); server.shutdown() }
    }
    @Test fun validatesOptionalKeyAndPackageWithoutShellSyntax() {
        assertEquals(1017, VoiceSettings().validate())
        assertEquals(0, VoiceSettings(keyCode = "").validate())
        assertEquals(65535, VoiceSettings(keyCode = "65535").validate())
        for (key in listOf("0", "-1", "65536", "1.5", "1017;reboot")) assertFailsWith<IllegalArgumentException> { VoiceSettings(keyCode = key).validate() }
        for (name in listOf("", "android", "com..a", "com.1a", "com.a;reboot", "com.a\n")) assertFailsWith<IllegalArgumentException> { VoiceSettings(packageName = name).validate() }
    }
    @Test fun startupBurstPreservesEveryByteAndWaitsForFullDrain(): Unit = runBlocking {
        Peer().use { peer ->
            RuntimeVoiceStream(peer.session) { peer.session }.use { stream ->
                stream.open(VoiceSettings())
                val pcm = ByteArray(32000) { (it % 251).toByte() }
                for (offset in pcm.indices step 640) stream.send(pcm.copyOfRange(offset, offset + 640), 640)
                stream.finish()
                assertContentEquals(pcm, peer.received())
                assertEquals("/voice", peer.server.takeRequest().path)
            }
        }
    }
    @Test fun routePreparedDoesNotMeanReceiverReady(): Unit = runBlocking {
        Peer(autoReady = false).use { peer ->
            RuntimeVoiceStream(peer.session) { peer.session }.use { stream ->
                val opening = async { stream.open(VoiceSettings()) }
                withTimeout(2000) { peer.started.await() }
                assertFalse(opening.isCompleted)
                assertEquals(0, peer.bytes.get())
                peer.ready(); withTimeout(2000) { opening.await() }
                stream.stop()
            }
        }
    }
    @Test fun creditsPauseNetworkUntilTargetConsumesWithoutDroppingFrames(): Unit = runBlocking {
        Peer(credit = 3200, autoCredit = false).use { peer ->
            RuntimeVoiceStream(peer.session) { peer.session }.use { stream ->
                stream.open(VoiceSettings()); stream.send(ByteArray(3200) { 1 }, 3200)
                val next = async { stream.send(byteArrayOf(2, 3), 2) }
                delay(100)
                assertFalse(next.isCompleted)
                peer.socket.send("{\"consumedBytes\":3200}")
                withTimeout(2000) { next.await() }
                stream.finish()
                assertEquals(3202, peer.received().size)
                assertContentEquals(byteArrayOf(2, 3), peer.received().takeLast(2).toByteArray())
            }
        }
    }
    @Test fun targetChangePreventsAudioFromReachingPreviousSession(): Unit = runBlocking {
        Peer().use { peer ->
            var current: RuntimeSession? = peer.session
            RuntimeVoiceStream(peer.session) { current }.use { stream ->
                stream.open(VoiceSettings()); current = null
                assertFailsWith<IllegalStateException> { stream.send(ByteArray(640), 640) }
                assertEquals(0, peer.bytes.get())
                stream.stop()
            }
        }
    }
    @Test fun cancelledHandshakeSendsCancelBeforeReceiverBecomesReady(): Unit = runBlocking {
        Peer(autoReady = false).use { peer ->
            RuntimeVoiceStream(peer.session) { peer.session }.use { stream ->
                val opening = launch { stream.open(VoiceSettings()) }
                withTimeout(2000) { peer.started.await() }; opening.cancelAndJoin()
                stream.stop()
                assertTrue(peer.cancelled.isCompleted)
                assertEquals(0, peer.bytes.get())
            }
        }
    }
    @Test fun routingFailureIsReportedBeforeRecordingIsReady(): Unit = runBlocking {
        Peer(reject = true).use { peer ->
            RuntimeVoiceStream(peer.session) { peer.session }.use { stream ->
                assertEquals("routing denied", assertFailsWith<IllegalStateException> { stream.open(VoiceSettings()) }.message)
            }
        }
    }
    @Test fun incompleteDrainCannotBeReportedAsSuccess(): Unit = runBlocking {
        Peer(truncateEnd = true).use { peer ->
            RuntimeVoiceStream(peer.session) { peer.session }.use { stream ->
                stream.open(VoiceSettings()); stream.send(ByteArray(640), 640)
                assertEquals("语音未完整送达目标", assertFailsWith<IllegalStateException> { stream.finish() }.message)
            }
        }
    }
    @Test fun captureBufferCoalescesPartialReadsAndRetainsBothEnds(): Unit = runBlocking {
        val buffer = BufferedVoicePcm(4)
        val first = ByteArray(102) { it.toByte() }; val last = ByteArray(900) { (255 - it).toByte() }
        buffer.append(first, first.size); buffer.append(last, last.size); buffer.finish()
        val output = ByteArrayOutputStream()
        while (true) { val frame = buffer.read() ?: break; output.write(frame) }
        assertContentEquals(first + last, output.toByteArray())
        assertEquals(1002L, buffer.capturedBytes)
    }
    @Test fun captureOverflowFailsInsteadOfReplacingTheFirstFrame(): Unit = runBlocking {
        val buffer = BufferedVoicePcm(1)
        val pcm = ByteArray(1280) { it.toByte() }
        assertFailsWith<IllegalStateException> { buffer.append(pcm, pcm.size) }
        assertContentEquals(pcm.copyOfRange(0, 640), buffer.read())
    }
}
