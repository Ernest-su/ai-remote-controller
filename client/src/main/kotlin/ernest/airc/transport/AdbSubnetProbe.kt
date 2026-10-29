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

data class DiscoveredAdbDevice(
    val host: String,
    val model: String? = null,
    val product: String? = null,
    val device: String? = null,
) {
    val name: String get() = model ?: product ?: device ?: "Android 设备 · $host"
}

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

    fun probe(host: String, port: Int = 5555, timeoutMs: Int = 450): DiscoveredAdbDevice? = runCatching {
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
            if (magic != response.inv() || size !in 0..4096) return@use null
            val data = ByteArray(size).also { input.readFully(it) }
            if (data.sumOf { it.toInt() and 255 } != checksum) return@use null
            when (response) {
                command -> {
                    val banner = data.toString(Charsets.UTF_8).substringBefore('\u0000')
                    if (arg0 < 0x01000000 || arg1 <= 0 || !banner.startsWith("device::")) return@use null
                    val properties = banner.removePrefix("device::").split(';')
                        .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
                    fun property(key: String): String? = properties[key]?.filter {
                        !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt()
                    }?.trim()?.take(80)?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                    DiscoveredAdbDevice(host, property("ro.product.model"), property("ro.product.name"), property("ro.product.device"))
                }
                0x48545541 -> if (arg0 == 1 && size == 20) DiscoveredAdbDevice(host) else null // No automatic authorization.
                else -> null
            }
        }
    }.getOrNull()

    suspend fun scan(hosts: List<String>, onFound: (DiscoveredAdbDevice) -> Unit) = withContext(Dispatchers.IO) {
        val permits = Semaphore(24)
        coroutineScope {
            hosts.map { host -> async {
                permits.withPermit {
                    currentCoroutineContext().ensureActive()
                    val found = probe(host)
                    currentCoroutineContext().ensureActive()
                    if (found != null) onFound(found)
                }
            } }.awaitAll()
        }
    }
}
