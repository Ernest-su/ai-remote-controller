package ernest.airc

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import kotlin.test.*

class ScreenPreviewReaderTest {
    private val id = "00000000-0000-4000-8000-000000000000"
    private val descriptor = ScreenPreviewDescriptor(id,"/preview/$id","application/x-airc-preview","airc-png-v1",1000,60,30,ScreenPreviewReader.MAX_FRAME_BYTES)
    private fun png(width: Int, height: Int) = ByteArray(33).also {
        byteArrayOf(137.toByte(),80,78,71,13,10,26,10).copyInto(it)
        ByteBuffer.wrap(it).apply { putInt(8,13); putInt(12,0x49484452); putInt(16,width); putInt(20,height) }
    }
    private fun record(sequence: Int, width: Int, height: Int, payload: ByteArray, clock: Long = sequence * 1000L, size: Int = payload.size) = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { it.writeInt(sequence); it.writeInt(width); it.writeInt(height); it.writeInt(size); it.writeLong(clock); it.write(payload) }
    }.toByteArray()
    @Test fun rotationChangesDimensionsWithoutBufferingTheWholeStream() {
        val frames = mutableListOf<ScreenPreviewFrame>()
        val stream = "AIRCPRV1".toByteArray() + record(1,2,1,png(2,1)) + record(2,1,2,png(1,2)) + record(2,0,0,byteArrayOf())
        ScreenPreviewReader.read(stream.inputStream(),descriptor,frames::add)
        assertEquals(listOf(2,1),frames.map { it.width }); assertEquals(listOf(1L,2L),frames.map { it.sequence })
    }
    @Test fun corruptTruncatedOversizedAndOutOfOrderStreamsAreRejected() {
        val magic = "AIRCPRV1".toByteArray()
        for(stream in listOf(magic + record(1,2,1,png(1,2)), magic + record(2,1,1,png(1,1)),
            magic + record(1,1,1,byteArrayOf(),size=ScreenPreviewReader.MAX_FRAME_BYTES+1),
            magic + record(1,1,1,png(1,1)).dropLast(1),magic + record(0,0,0,"capture failed".toByteArray()),
            magic + record(1,1,1,png(1,1)), "wrongver".toByteArray())) {
            assertFails { ScreenPreviewReader.read(stream.inputStream(),descriptor) {} }
        }
        assertFails { ScreenPreviewReader.validate(descriptor.copy(path="/status")) }
    }
    @Test fun httpReaderRejectsRedirectsAndCancellationClosesAStalledStream(): Unit = runBlocking {
        val server = MockWebServer(); server.start(); val client = RuntimePreviewClient()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/status"))
            assertFails { client.read(server.hostName,server.port,descriptor) {} }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = async { client.read(server.hostName,server.port,descriptor) {} }
            withContext(Dispatchers.IO) { server.takeRequest(); server.takeRequest() }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { client.close(); server.shutdown() }
    }
}
