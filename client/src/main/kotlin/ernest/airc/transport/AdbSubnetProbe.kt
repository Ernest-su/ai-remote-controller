package ernest.airc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read-only ADB handshake: never sends AUTH, OPEN, pairing or shell requests. */
object AdbSubnetProbe {
    fun hosts(address: String, prefix: Int): List<String> {
        require(prefix in 1..32)
        val parts = address.split('.').map { it.toInt() }
        require(parts.size == 4 && parts.all { it in 0..255 })
        val ip = parts.fold(0L) { value, part -> (value shl 8) or part.toLong() }
        val bits = prefix.coerceAtLeast(22)
        val actual = if (prefix < 22) 24 else bits
        if (actual >= 31) return emptyList()
        val mask = (0xffffffffL shl (32 - actual)) and 0xffffffffL
        val base = ip and mask
        return (1L until (1L shl (32 - actual)) - 1).map { base + it }.filter { it != ip }
            .map { n -> listOf(24, 16, 8, 0).joinToString(".") { shift -> ((n shr shift) and 255).toString() } }
    }

    fun responds(host: String, port: Int = 5555, timeoutMs: Int = 450): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val payload = "host::\u0000".toByteArray()
            val command = 0x4e584e43
            socket.getOutputStream().write(ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(0x01000000).putInt(4096).putInt(payload.size)
                .putInt(payload.sumOf { it.toInt() and 255 }).putInt(command.inv()).put(payload).array())
            val input = java.io.DataInputStream(socket.getInputStream())
            val header = ByteArray(24).also { input.readFully(it) }
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val response = buffer.int
            val arg0 = buffer.int; val arg1 = buffer.int
            val size = buffer.int; val checksum = buffer.int; val magic = buffer.int
            if (magic != response.inv() || size !in 0..4096) return@use false
            val data = ByteArray(size).also { input.readFully(it) }
            if (data.sumOf { it.toInt() and 255 } != checksum) return@use false
            when (response) {
                command -> arg0 >= 0x01000000 && arg1 > 0 && data.toString(Charsets.UTF_8).startsWith("device::")
                0x48545541 -> arg0 == 1 && size == 20 // AUTH token; authorization is a separate user action.
                else -> false
            }
        }
    }.getOrDefault(false)

    suspend fun scan(hosts: List<String>, onFound: (String) -> Unit) = withContext(Dispatchers.IO) {
        val permits = Semaphore(24)
        coroutineScope {
            hosts.map { host -> async {
                permits.withPermit {
                    currentCoroutineContext().ensureActive()
                    val found = responds(host)
                    currentCoroutineContext().ensureActive()
                    if (found) onFound(host)
                }
            } }.awaitAll()
        }
    }
}
