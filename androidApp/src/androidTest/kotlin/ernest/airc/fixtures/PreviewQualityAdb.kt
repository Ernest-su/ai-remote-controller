package ernest.airc

import ernest.ascrcpy.adb.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Supplies real codec/size headers to MediaCodec; no remote connection or synthetic frame claims. */
internal class PreviewQualityAdb : AdbClient {
    override val state = MutableStateFlow(AdbConnectionState.Disconnected)
    val command = CompletableDeferred<String>()
    val channels = CopyOnWriteArrayList<AdbChannel>()
    val removals = AtomicInteger()
    @Volatile var closed = false
    private var pushed: String? = null
    private val streams = AtomicInteger()
    override suspend fun connect(endpoint: AdbEndpoint): AdbDevice = error("Unexpected connect")
    override suspend fun disconnect() { close() }
    override suspend fun pull(remotePath: String, sink: OutputStream) = error("Unexpected pull")
    override suspend fun push(source: InputStream, remotePath: String, mode: Int) {
        val buffer = ByteArray(4096)
        while (source.read(buffer) >= 0) { /* Consume the bundled server without uploading. */ }
        pushed = remotePath
        // Leave time for the retired Android view to be destroyed during transport startup.
        delay(200)
    }
    override suspend fun shell(command: String): AdbCommandResult {
        check(command == "rm -f -- ${checkNotNull(pushed)}")
        removals.incrementAndGet()
        return AdbCommandResult(byteArrayOf(), 0)
    }
    override suspend fun open(service: String): AdbChannel {
        val video = if (service.startsWith("shell:CLASSPATH=")) {
            command.complete(service)
            false
        } else {
            check(service.startsWith("localabstract:scrcpy_"))
            streams.getAndIncrement() == 0
        }
        return object : AdbChannel {
            val ended = CompletableDeferred<Unit>()
            var payload: ByteArray? = null
            var position = 0
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (video && payload == null) {
                    val size = Regex("max_size=(\\d+)").find(command.await())!!.groupValues[1].toInt()
                    payload = ByteBuffer.allocate(16).putInt(0x68323634).putInt(Int.MIN_VALUE)
                        .putInt(size).putInt((size * 9 / 16) / 8 * 8).array()
                }
                val bytes = payload
                if (bytes != null && position < bytes.size) {
                    val count = minOf(length, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + count)
                    position += count
                    return count
                }
                ended.await()
                return -1
            }
            override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = error("Unexpected control write")
            override fun close() { ended.complete(Unit) }
        }.also { channels += it }
    }
    override fun close() { closed = true; channels.forEach { it.close() } }
}
