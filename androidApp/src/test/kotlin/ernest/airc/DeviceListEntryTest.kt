package ernest.airc

import org.junit.Assert.*
import org.junit.Test

class DeviceListEntryTest {
    private fun device(id: String, host: String, time: Long) = RememberedDevice(id, "TV", ConnectionMethod.TCP,
        host = host, adbPort = 5555, lastConnectedAt = time)
    @Test fun combinesThreeSourcesAndPreservesHistoricalIdentities() {
        val history = listOf(device("old", "192.168.68.223", 1), device("new", "192.168.68.223", 2), device("other", "192.168.68.59", 3))
        val service = DiscoveredService("Runtime", "192.168.68.223", 9876, emptyMap())
        val rows = deviceListEntries(RemoteUiState(history = history, discovered = listOf(service), adbDevices = listOf("192.168.68.223"), selected = "old"))
        assertEquals(2, rows.size)
        assertEquals("192.168.68.223", rows.first().host)
        assertEquals(listOf("new", "old"), rows.first().history.map { it.id })
        assertEquals(service, rows.first().service)
        assertNotNull(rows.first().adbHost)
    }
    @Test fun adbOnlyDevicesAppearBeforeSavedOfflineRows() {
        val rows = deviceListEntries(RemoteUiState(history = listOf(device("old", "192.168.68.59", 1)), adbDevices = listOf("192.168.68.223")))
        assertEquals("192.168.68.223", rows.first().host)
        assertTrue(rows.first().history.isEmpty())
        assertNull(rows.first().service)
    }
}
