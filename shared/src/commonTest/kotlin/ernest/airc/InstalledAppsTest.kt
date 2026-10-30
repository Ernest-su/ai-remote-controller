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

    @Test fun storageAndInstallMetadataPreserveUnknownAndZero() {
        val app=decodeInstalledApps("""{"sizesRequested":true,"apps":[{"package":"a.app","firstInstallTime":123,"sizeBytes":0}]}""").single()
        assertEquals(123L,app.firstInstallTime);assertEquals(0L,app.sizeBytes);assertEquals(true,app.sizeQueried)
        assertNull(decodeInstalledApps("""{"apps":[{"package":"a.app","firstInstallTime":0}]}""").single().firstInstallTime)
        assertFailsWith<IllegalArgumentException>{decodeInstalledApps("""{"apps":[{"package":"a.app","sizeBytes":-1}]}""")}
    }
    @Test fun sortWholeListBeforePagingAndKeepUnknownLastInBothDirections() {
        val apps=(0..64).map { InstalledApp("app.$it", "App"+it.toString().padStart(2,'0'),firstInstallTime=it.toLong()+1,sizeBytes=it.toLong()) }
        val unknown=InstalledApp("unknown.app","Unknown")
        assertEquals("app.64",sortInstalledApps(apps+unknown,AppSort.SIZE).take(30).first().packageName)
        assertEquals(unknown,sortInstalledApps(apps+unknown,AppSort.SIZE).last())
        assertEquals(unknown,sortInstalledApps(apps+unknown,AppSort.SIZE,false).last())
        assertEquals("app.0",sortInstalledApps(apps+unknown,AppSort.SIZE,false).first().packageName)
        assertEquals("app.64",sortInstalledApps(apps+unknown,AppSort.INSTALLED).first().packageName)
        assertEquals("app.0",sortInstalledApps(apps+unknown,AppSort.INSTALLED,false).first().packageName)
    }
    @Test fun explicitSortUsesNamesAsStableTiebreakersAndDoesNotPinFavorites() {
        val a=InstalledApp("p.a","Alpha",sizeBytes=10)
        val b=InstalledApp("p.b","beta",sizeBytes=10)
        val unnamed=InstalledApp("p.c",sizeBytes=0)
        assertEquals(listOf(a,b,unnamed),sortInstalledApps(listOf(unnamed,b,a),AppSort.NAME))
        assertEquals(listOf(b,a),sortInstalledApps(listOf(a,b),AppSort.NAME,true))
        assertEquals(listOf(a,b),sortInstalledApps(listOf(b,a),AppSort.SIZE,favorites=listOf("p.b")))
        assertEquals(listOf(b,a),sortInstalledApps(listOf(a,b),AppSort.DEFAULT,favorites=listOf("p.b")))
    }
}
