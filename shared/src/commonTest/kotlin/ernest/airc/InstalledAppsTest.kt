package ernest.airc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InstalledAppsTest {
    @Test fun structuredAndLegacyListsPreserveMetadata() {
        val apps = decodeInstalledApps("""{"apps":[{"package":"com.example.tv","label":"电视","system":false}]}""")
        assertEquals(InstalledApp("com.example.tv", "电视", false), apps.single())
        val legacy = decodeInstalledApps("package:com.android.settings\n").single()
        assertNull(legacy.label)
        assertNull(legacy.system)
        assertFailsWith<IllegalArgumentException> { decodeInstalledApps("Failure [permission denied]") }
    }

    @Test fun searchingMatchesLabelOrPackageIgnoringCaseAndWhitespace() {
        val apps = listOf(InstalledApp("com.example.tv", "客厅电视"), InstalledApp("com.android.settings"))
        assertEquals(listOf(apps[0]), filterInstalledApps(apps, "电视"))
        assertEquals(listOf(apps[1]), filterInstalledApps(apps, "  SETTINGS "))
        assertEquals(apps, filterInstalledApps(apps, ""))
        assertEquals(emptyList(), filterInstalledApps(apps, "missing"))
    }
}
