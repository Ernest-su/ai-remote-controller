package ernest.airc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class HistoryTest {
    @Test fun displayNameSurvivesIdentityMigrationAndReconnect() {
        val old = RememberedDevice("adb:guid", "TV", ConnectionMethod.TCP, lastConnectedAt = 1)
        val renamed = renameDevice(listOf(old), old.id, " 客厅电视 ")
        val migrated = rememberDevice(renamed, old.copy(id = "uuid", name = "New model"), old.id)
        assertEquals("客厅电视", migrated.single().visibleName)
        val reconnected = rememberDevice(migrated, migrated.single().copy(name = "New runtime name", displayName = null))
        assertEquals("客厅电视", reconnected.single().visibleName)
        assertEquals("New runtime name", reconnected.single().name)
        assertFails { renameDevice(renamed, "missing", "名称") }
        assertFails { validatedDeviceName(" ") }
        assertFails { validatedDeviceName("电视\n控制字符") }
        assertFails { validatedDeviceName("a".repeat(65)) }
    }
    @Test fun runtimeIdentityReplacesTheSuccessfulAdbRecord() {
        val adb = RememberedDevice("adb:guid", "TV", ConnectionMethod.WIRELESS_CODE,
            "192.168.1.2", 30000, wirelessGuid = "guid", lastConnectedAt = 1)
        val runtime = adb.copy(id = "runtime-uuid", runtimePort = 9876, lastConnectedAt = 2)
        assertEquals(listOf(runtime), rememberDevice(listOf(adb), runtime, previousId = adb.id))
    }
    @Test fun successfulReconnectReplacesOldEndpoint() {
        val old = RememberedDevice("guid", "TV", ConnectionMethod.WIRELESS_CODE,
            "192.168.1.2", 30000, wirelessGuid = "guid", lastConnectedAt = 1)
        val updated = old.copy(adbPort = 40000, lastConnectedAt = 3)
        val other = old.copy(id = "other", lastConnectedAt = 2)
        assertEquals(listOf(updated, other), rememberDevice(listOf(old, other), updated))
    }
}
