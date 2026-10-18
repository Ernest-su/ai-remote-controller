package ernest.airc
import kotlin.test.*
class AppShortcutsTest {
    @Test fun ordersFavoritesAndBoundsHistory() {
        val apps = AppShortcuts().toggle("a").toggle("b").move("b", -1)
        assertEquals(listOf("b", "a"), apps.favorites)
        assertEquals(listOf("b"), apps.toggle("a").favorites)
        val history = (0..20).fold(apps) { value, n -> value.launched("p$n") }.launched("p20")
        assertEquals(12, history.recent.size)
        assertEquals("p20", history.recent.first())
        assertEquals(history, history.move("missing", 1))
    }
}
