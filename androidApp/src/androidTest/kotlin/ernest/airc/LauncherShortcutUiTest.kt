package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated dialog and platform metadata; does not request a launcher pin or contact a TV. */
class LauncherShortcutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val draft = LauncherShortcutDraft("fixture", "tv-1", "客厅电视", "routine", "movie", "客厅电视 · 开始观影")

    @Test fun defaultNameAndSelectedIconCanBeEditedAndRestoredInBothThemes() {
        var dark by mutableStateOf(false)
        var submitted: Pair<String, String>? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RemoteTheme(darkTheme = dark) { LauncherShortcutNameDialog(draft, { name, icon -> submitted = name to icon }, {}) }
            }
        }
        compose.onNodeWithTag("launcher-shortcut-name").assertTextContains(draft.label)
        compose.onNodeWithTag("launcher-icon-default").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("launcher-shortcut-name").performScrollTo().performTextReplacement("  晚间观影  ")
        compose.onNodeWithTag("launcher-icon-play").performScrollTo().performClick().assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("launcher-shortcut-name").performScrollTo().assertTextContains("  晚间观影  ")
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            compose.onNodeWithTag("launcher-icon-play").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("launcher-shortcut-confirm").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("晚间观影" to "play", submitted) }
        }
    }

    @Test fun invalidNamesDisableSubmissionAndCancelDoesNotSubmit() {
        var submitted = false
        var dismissed = false
        compose.setContent { RemoteTheme { LauncherShortcutNameDialog(draft.copy(icon = "clock"), { _, _ -> submitted = true }, { dismissed = true }) } }
        compose.onNodeWithTag("launcher-icon-clock").performScrollTo().assertIsSelected()
        for (name in listOf("   ", "长".repeat(121))) {
            compose.onNodeWithTag("launcher-shortcut-name").performScrollTo().performTextReplacement(name)
            compose.onNodeWithTag("launcher-shortcut-confirm").assertIsNotEnabled()
        }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(submitted) }
        assertFalse(validLauncherName("名称\n换行"))
        assertTrue(validLauncherName("名".repeat(120)))
    }

    @Test fun savedNamesAndIconsReachSystemShortcutWithoutChangingAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val action = LauncherAction("token-fixture", "tv-1", "routine", "movie", "晚间观影", "play")
        val restored = kotlinx.serialization.json.Json.decodeFromString<LauncherAction>(kotlinx.serialization.json.Json.encodeToString(action))
        val shortcut = LauncherActions.shortcut(context, restored)
        assertEquals("晚间观影", shortcut.shortLabel)
        assertEquals("晚间观影", shortcut.longLabel)
        assertEquals(R.drawable.ic_play, launcherIcon(restored.icon).resource)
        assertEquals(LauncherActions.intent(context, action).data, shortcut.intent!!.data)
        val defaultAction = action.copy(icon = "default")
        assertEquals(R.drawable.ic_ai_rc, launcherIcon(defaultAction.icon).resource)
        val preferences = context.getSharedPreferences("launcher-actions", 0)
        val previous = preferences.getString("items", null)
        try {
            preferences.edit().putString("items", kotlinx.serialization.json.Json.encodeToString(listOf(restored))).commit()
            assertEquals(action, LauncherActions.all(context).single())
            assertEquals(action, LauncherActions.resolve(context, shortcut.intent))
        } finally {
            preferences.edit().apply { if (previous == null) remove("items") else putString("items", previous) }.commit()
        }
    }
}
