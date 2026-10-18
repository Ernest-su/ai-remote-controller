package ernest.airc

import kotlin.test.*

class LibraryEditingTest {
    @Test fun favoritesStayAheadOfPaginationAndKeepTheirSavedOrder() {
        val apps = (0..50).map { InstalledApp("fixture.p$it", "应用 $it", it % 2 == 0) }
        val ordered = favoriteFirstApps(apps, listOf("fixture.p50", "fixture.p30"))
        assertEquals(listOf("fixture.p50", "fixture.p30"), ordered.take(2).map { it.packageName })
        assertEquals(apps.filterNot { it.packageName in listOf("fixture.p50", "fixture.p30") }, ordered.drop(2))
        assertEquals(listOf(apps[50]), favoriteFirstApps(filterInstalledApps(apps, "应用 50"), listOf("fixture.p50")))
    }
    @Test fun secondsEditorPreservesMillisecondsAndRejectsOutOfRangeInput() {
        for (milliseconds in listOf(100L, 500L, 1000L, 1500L, 10000L)) assertEquals(milliseconds, parseRoutineSeconds(routineSeconds(milliseconds)))
        for (value in listOf("", "-1", "0.09", "10.001", "11", "1.0001", "1.2.3", "1e2", ".5", "1.")) assertNull(parseRoutineSeconds(value))
    }
    @Test fun movingStepsPreservesContentAndDoesNotMutateOriginal() {
        val original = listOf(RoutineStep("key", "ok"), RoutineStep("wait", "1500"), RoutineStep("app", "fixture.player"))
        assertEquals(listOf(original[1], original[0], original[2]), moveRoutineStep(original, 1, -1))
        assertEquals(original, moveRoutineStep(original, 0, -1))
        assertEquals(original, moveRoutineStep(original, 2, 1))
        assertEquals("ok", original.first().value)
    }
}
