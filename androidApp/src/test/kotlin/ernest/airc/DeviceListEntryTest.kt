package ernest.airc

import org.junit.Assert.*
import org.junit.Test

class DeviceListEntryTest {
    private fun device(id: String, host: String, time: Long) = RememberedDevice(id, "TV", ConnectionMethod.TCP,
        host = host, adbPort = 5555, lastConnectedAt = time)
    @Test fun combinesThreeSourcesAndPreservesHistoricalIdentities() {
        val history = listOf(device("old", "192.168.68.223", 1), device("new", "192.168.68.223", 2), device("other", "192.168.68.59", 3))
        val service = DiscoveredService("Runtime", "192.168.68.223", 9876, emptyMap())
        val rows = deviceListEntries(RemoteUiState(history = history, discovered = listOf(service), adbDevices = listOf(DiscoveredAdbDevice("192.168.68.223")), selected = "old"))
        assertEquals(2, rows.size)
        assertEquals("192.168.68.223", rows.first().host)
        assertEquals(listOf("new", "old"), rows.first().history.map { it.id })
        assertEquals(service, rows.first().service)
        assertNotNull(rows.first().adbHost)
    }
    @Test fun adbOnlyDevicesAppearBeforeSavedOfflineRows() {
        val rows = deviceListEntries(RemoteUiState(history = listOf(device("old", "192.168.68.59", 1)), adbDevices = listOf(DiscoveredAdbDevice("192.168.68.223"))))
        assertEquals("192.168.68.223", rows.first().host)
        assertTrue(rows.first().history.isEmpty())
        assertNull(rows.first().service)
    }
    @Test fun adbRowsUseModelsAndDistinctAddressFallbacks() {
        val rows = deviceListEntries(RemoteUiState(adbDevices = listOf(
            DiscoveredAdbDevice("192.0.2.1", model = "Living Room TV"),
            DiscoveredAdbDevice("192.0.2.2"), DiscoveredAdbDevice("192.0.2.3"))))
        assertEquals(listOf("Living Room TV", "Android 设备 · 192.0.2.2", "Android 设备 · 192.0.2.3"), rows.map { it.name })
    }
    @Test fun savedNamesAndServiceNamesTakePriorityOverAdbModel() {
        val saved = device("saved", "192.0.2.1", 1).copy(displayName = "客厅电视")
        val adb = DiscoveredAdbDevice("192.0.2.1", model = "Hardware model")
        val service = DiscoveredService("Runtime", adb.host, 9876, mapOf("name" to "Service name"))
        assertEquals("客厅电视", deviceListEntries(RemoteUiState(history = listOf(saved), discovered = listOf(service), adbDevices = listOf(adb))).single().name)
        assertEquals("Service name", deviceListEntries(RemoteUiState(discovered = listOf(service), adbDevices = listOf(adb))).single().name)
        assertEquals("Hardware model", deviceListEntries(RemoteUiState(discovered = listOf(service.copy(name = "", attributes = mapOf("name" to " "))), adbDevices = listOf(adb))).single().name)
    }
    @org.junit.Test fun pendingUnsavedTargetAppearsAndDoesNotMarkPreviousSelection() {
        val old=device("old","192.0.2.10",1)
        val target=device("pending","192.0.2.11",2)
        val state=RemoteUiState(history=listOf(old), selected=old.id, reconnecting=true, connectingDevice=target)
        val rows=deviceListEntries(state)
        val pending=rows.single { it.host==target.host }
        assertTrue(pending.isConnecting(state))
        assertEquals(target.visibleName,pending.name)
        assertTrue(pending.history.isEmpty())
        assertFalse(rows.single { it.host==old.host }.isConnecting(state))
        assertEquals(1,deviceListEntries(state.copy(reconnecting=false,connectingDevice=null)).size)
    }
}
