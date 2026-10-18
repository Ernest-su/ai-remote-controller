package ernest.airc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InstalledAppsTest {
    @Test fun structuredListsPreserveVersionsAndExplicitFallbackMetadata() {
        val key = "a".repeat(64)
        val apps = decodeInstalledApps("""{"apps":[{"package":"com.example.tv","label":"电视","system":false,"cacheKey":"$key"}]}""")
        assertEquals(InstalledApp("com.example.tv", "电视", false, key), apps.single())
        val fallback = decodeInstalledApps("""{"apps":[{"package":"com.android.settings","label":null,"system":true}],"source":"pm","labelsAvailable":false}""").single()
        assertNull(fallback.label); assertNull(fallback.cacheKey)
        assertFailsWith<IllegalArgumentException> { decodeInstalledApps("package:com.android.settings\n") }
        assertFailsWith<IllegalArgumentException> { decodeInstalledApps("Failure [permission denied]") }
        assertFailsWith<IllegalArgumentException> { decodeInstalledApps("""{"apps":[{"package":"com.example.tv","cacheKey":"bad"}]}""") }
    }

    @Test fun searchingMatchesLabelOrPackageIgnoringCaseAndWhitespace() {
        val apps = listOf(InstalledApp("com.example.tv", "客厅电视"), InstalledApp("com.android.settings"))
        assertEquals(listOf(apps[0]), filterInstalledApps(apps, "电视"))
        assertEquals(listOf(apps[1]), filterInstalledApps(apps, "  SETTINGS "))
        assertEquals(apps, filterInstalledApps(apps, ""))
        assertEquals(emptyList(), filterInstalledApps(apps, "missing"))
    }
}
