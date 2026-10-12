package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import kotlin.test.*

class DesktopDiscoveryTest {
    @Test fun advertisementsMustMatchProtocolAddressAndServicePort() {
        val target = InetAddress.getByName("192.0.2.1")
        assertTrue(DesktopDiscovery.matches("_airc._tcp.local.", 9876, listOf(target), "1", target, 9876))
        assertFalse(DesktopDiscovery.matches("_airc._tcp.local.", 9877, listOf(target), "1", target, 9876))
        assertFalse(DesktopDiscovery.matches("_airc._tcp.local.", 9876, listOf(target), "2", target, 9876))
        assertFalse(DesktopDiscovery.matches("_other._tcp.local.", 9876, listOf(target), "1", target, 9876))
        assertFalse(DesktopDiscovery.matches("_airc._tcp.local.", 9876, emptyList(), "1", target, 9876))
    }
    @Test fun adbEndpointMappingNeverTreatsUsbSerialAsLanAddress() {
        assertEquals("192.0.2.1", DesktopDiscovery.tcpAdbHost("192.0.2.1:5555"))
        assertNull(DesktopDiscovery.tcpAdbHost("emulator-5554"))
        assertNull(DesktopDiscovery.tcpAdbHost("192.0.2.1:"))
        assertNull(DesktopDiscovery.tcpAdbHost("192.0.2.1:65536"))
        assertNull(DesktopDiscovery.tcpAdbHost("user@192.0.2.1:5555"))
    }
    @Test fun loopbackCannotProveTargetMulticastDiscovery(): Unit = runBlocking {
        assertEquals(DiagnosticOutcome.SKIPPED, DesktopDiscovery().probe("127.0.0.1", 9876).outcome)
    }
}
