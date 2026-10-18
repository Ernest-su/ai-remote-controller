package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class ApplicationLibraryUiTest {
    @get:Rule val compose = createComposeRule()
    private val apps = listOf(InstalledApp("fixture.user", "用户播放器", false), InstalledApp("fixture.system", "系统设置", true), InstalledApp("fixture.other", "其他应用", false))
    @Test fun favoriteButtonDoesNotLaunchAndFavoritesMoveToTheFront() {
        var favorites by mutableStateOf(listOf("fixture.system"))
        var launched: String? = null
        compose.setContent { RemoteTheme { ApplicationLibraryPanel(apps, favorites, true, false, null, {}, { launched = it.packageName }, onFavorite = { name -> favorites = AppShortcuts(favorites).toggle(name).favorites }) } }
        val systemY = compose.onNodeWithTag("app-row-fixture.system").fetchSemanticsNode().positionInRoot.y
        val userY = compose.onNodeWithTag("app-row-fixture.user").fetchSemanticsNode().positionInRoot.y
        assertTrue(systemY < userY)
        compose.onNodeWithTag("app-favorite-fixture.system").assertIsOn().performClick().assertIsOff()
        assertNull(launched)
        compose.onNodeWithTag("app-favorite-fixture.other").performClick().assertIsOn()
        assertEquals(listOf("fixture.other"), favorites)
        compose.onNodeWithText("其他应用").performClick()
        assertEquals("fixture.other", launched)
    }
    @Test fun filtersAndSearchApplyToTheWholeList() {
        compose.setContent { RemoteTheme { ApplicationLibraryPanel(apps, emptyList(), true, false, null, {}, {}, onFavorite = {}) } }
        compose.onNodeWithTag("library-filter-system").performClick()
        compose.onNodeWithTag("app-row-fixture.system").assertExists()
        compose.onNodeWithTag("app-row-fixture.user").assertDoesNotExist()
        compose.onNodeWithTag("library-filter-user").performClick()
        compose.onNodeWithTag("app-row-fixture.user").assertExists()
        compose.onNodeWithTag("app-row-fixture.system").assertDoesNotExist()
        compose.onNodeWithTag("library-search").performTextInput("其他")
        compose.onNodeWithTag("app-row-fixture.other").assertExists()
        compose.onNodeWithTag("app-row-fixture.user").assertDoesNotExist()
    }
    @Test fun fullFavoritesCanBeRemovedOfflineButCannotLaunch() {
        var favorites by mutableStateOf((0..11).map { "fixture.p$it" })
        var launched = false
        compose.setContent { RemoteTheme { ApplicationLibraryPanel(apps, favorites, false, false, null, {}, { launched = true }, onFavorite = { name -> favorites = AppShortcuts(favorites).toggle(name).favorites }) } }
        compose.onNodeWithTag("library-search").performTextInput("fixture.user")
        compose.onNodeWithTag("app-favorite-fixture.user").assertIsNotEnabled()
        compose.onNodeWithText("用户播放器").assertIsNotEnabled()
        assertFalse(launched)
        compose.onNodeWithTag("library-search").performTextReplacement("fixture.p0")
        compose.onNodeWithTag("app-favorite-fixture.p0").assertIsEnabled().assertIsOn().performClick()
        assertFalse("fixture.p0" in favorites)
    }
    @Test fun applicationManagementKeepsFavoriteAndLaunchActionsSeparate() {
        var favorites by mutableStateOf(emptyList<String>())
        var launched: String? = null
        compose.setContent { RemoteTheme { ApplicationsPanel(apps, true, false, null, "", {}, { launched = it },
            favorites = favorites, onFavorite = { pkg -> favorites = AppShortcuts(favorites).toggle(pkg).favorites }) } }
        compose.onNodeWithTag("app-favorite-fixture.system").performClick().assertIsOn()
        assertNull(launched)
        assertEquals(listOf("fixture.system"), favorites)
        val systemY = compose.onNodeWithTag("app-row-fixture.system").fetchSemanticsNode().positionInRoot.y
        val userY = compose.onNodeWithTag("app-row-fixture.user").fetchSemanticsNode().positionInRoot.y
        assertTrue(systemY < userY)
        compose.onNodeWithTag("app-more-fixture.system").performClick()
        compose.onNodeWithText("启动应用").performClick()
        assertEquals("fixture.system", launched)
    }

    @Test fun sortingIgnoresSearchAndRecentFilterAndKeepsSavedOrder() {
        var favorites by mutableStateOf(listOf("fixture.system", "fixture.user"))
        compose.setContent { RemoteTheme { ApplicationLibraryPanel(apps, favorites, true, false, null, {}, {}, onFavorite = {},
            recent = listOf("fixture.system"), onMove = { pkg, direction -> favorites = AppShortcuts(favorites).move(pkg, direction).favorites }) } }
        compose.onNodeWithTag("library-filter-recent").performClick()
        compose.onNodeWithTag("library-search").performTextInput("系统")
        compose.onNodeWithTag("library-tools").performClick()
        compose.onNodeWithText("调整收藏顺序").performClick()
        compose.onNodeWithText("用户播放器").assertExists()
        compose.onNodeWithContentDescription("上移 用户播放器").performClick()
        assertEquals(listOf("fixture.user", "fixture.system"), favorites)
        compose.onNodeWithText("完成排序").performClick()
        compose.onNodeWithTag("app-row-fixture.system").assertExists()
        compose.onNodeWithTag("app-row-fixture.user").assertDoesNotExist()
    }

}
