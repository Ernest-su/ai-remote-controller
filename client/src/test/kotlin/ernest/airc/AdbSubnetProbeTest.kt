package ernest.airc

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class AdbSubnetProbeTest {
    @Test fun subnetRespectsMaskExcludesSelfAndBroadcastAndBoundsLargeNetworks() {
        val hosts = AdbSubnetProbe.hosts("192.168.68.40", 24)
        assertEquals(253, hosts.size)
        assertTrue("192.168.68.223" in hosts)
        assertFalse("192.168.68.40" in hosts)
        assertFalse("192.168.68.255" in hosts)
        assertFalse("192.168.68.0" in hosts)
        assertEquals(hosts, AdbSubnetProbe.hosts("192.168.68.40", 16))
        assertEquals(listOf("10.0.0.2"), AdbSubnetProbe.hosts("10.0.0.1", 30))
        assertTrue(AdbSubnetProbe.hosts("10.0.0.1", 32).isEmpty())
    }
    private fun probe(response: ByteArray): DiscoveredAdbDevice? = ServerSocket(0).use { server ->
        val request = CompletableFuture.runAsync {
            server.accept().use { socket ->
                socket.soTimeout = 1000
                val input = java.io.DataInputStream(socket.getInputStream())
                val header = ByteArray(24).also { input.readFully(it) }
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(0x4e584e43, buffer.int) // CNXN only; never AUTH or OPEN.
                val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
                input.readFully(ByteArray(size))
                socket.getOutputStream().write(response)
                socket.shutdownOutput()
                assertEquals(-1, input.read()) // Probe closes without sending AUTH, OPEN or shell.
            }
        }
        val found = AdbSubnetProbe.probe("127.0.0.1", server.localPort, 1000)
        request.get(2, TimeUnit.SECONDS)
        found
    }
    private fun packet(command: Int, arg0: Int, arg1: Int, body: ByteArray): ByteArray =
        ByteBuffer.allocate(24 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(body.size)
            .putInt(body.sumOf { it.toInt() and 255 }).putInt(command.inv()).put(body).array()

    @Test fun acceptsAdbConnectionAndAuthorizationToken() {
        assertNotNull(probe(packet(0x4e584e43, 0x01000000, 4096, "device::ro.product.model=TV\u0000".toByteArray())))
        assertNotNull(probe(packet(0x48545541, 1, 0, ByteArray(20) { it.toByte() })))
    }
    @Test fun readsModelProductAndDeviceFromConnectionBanner() {
        val found = probe(packet(0x4e584e43, 0x01000001, 4096,
            "device::ro.product.name=tv_product;ro.product.model=Living Room TV;ro.product.device=tv_code;features=shell_v2;\u0000".toByteArray()))!!
        assertEquals("127.0.0.1", found.host)
        assertEquals("Living Room TV", found.name)
        assertEquals("tv_product", found.product)
        assertEquals("tv_code", found.device)
    }
    @Test fun fallsBackFromBlankModelAndBoundsUntrustedNames() {
        fun banner(properties: String) = probe(packet(0x4e584e43, 0x01000000, 4096, "device::$properties\u0000".toByteArray()))!!
        assertEquals("product", banner("ro.product.model=  ;ro.product.name=product;ro.product.device=code").name)
        assertEquals("code", banner("ro.product.model=unknown;ro.product.device=code").name)
        assertEquals("Android 设备 · 127.0.0.1", banner("features=shell_v2").name)
        assertEquals("LivingRoom", banner("ro.product.model=Living\n\u202eRoom").name)
        assertEquals(80, banner("ro.product.model=${"x".repeat(100)}").name.length)
        assertEquals("Android 设备 · 127.0.0.1",
            probe(packet(0x48545541, 1, 0, ByteArray(20)))!!.name)
    }
    @Test fun rejectsOpenNonAdbPortCorruptChecksumAndWrongBanner() {
        assertNull(probe("HTTP/1.1 200 OK\r\n\r\n".toByteArray()))
        val packet = packet(0x4e584e43, 0x01000000, 4096, "device::\u0000".toByteArray())
        packet[16] = (packet[16].toInt() xor 1).toByte()
        assertNull(probe(packet))
        assertNull(probe(packet(0x4e584e43, 0x01000000, 4096, "host::\u0000".toByteArray())))
    }
}
