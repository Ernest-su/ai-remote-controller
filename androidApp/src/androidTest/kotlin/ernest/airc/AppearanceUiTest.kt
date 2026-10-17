package ernest.airc

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local appearance only; no external target is connected or modified. */
@RunWith(AndroidJUnit4::class)
class AppearanceUiTest {
    private val preferences = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val original = preferences.getString("theme", null)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun restorePreference() {
        preferences.edit().apply { if (original == null) remove("theme") else putString("theme", original) }.commit()
    }

    @Test fun appearancePersistsAndPreservesDraftNavigationAndSystemBars() {
        configuredOfflineComposer(compose)
        compose.onNodeWithTag("remote-command-input").performTextInput("保留外观切换草稿")
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-settings").performScrollTo().performClick()
        compose.onNodeWithTag("theme-dark").performClick().assertIsSelected()
        screenshot("settings-dark")
        compose.runOnIdle {
            assertEquals("DARK", preferences.getString("theme", null))
            val bars = WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
            assertFalse(bars.isAppearanceLightStatusBars)
            assertFalse(bars.isAppearanceLightNavigationBars)
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("settings-page").assertIsDisplayed()
        compose.onNodeWithTag("theme-dark").assertIsSelected()
        compose.onNodeWithTag("device-status").performClick()
        screenshot("devices-dark")
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("settings-page").assertIsDisplayed()
        compose.onNodeWithTag("theme-light").performClick()
        screenshot("settings-light")
        compose.runOnIdle {
            val bars = WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
            assertTrue(bars.isAppearanceLightStatusBars)
            assertTrue(bars.isAppearanceLightNavigationBars)
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("feature-menu").assertIsDisplayed()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("remote-command-input").assertTextContains("保留外观切换草稿")
        screenshot("remote-light")
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-settings").performScrollTo().performClick()
        compose.onNodeWithTag("theme-dark").performClick()
        compose.onNodeWithTag("navigate-back").performClick()
        screenshot("menu-dark")
        compose.onNodeWithTag("feature-model").performScrollTo().performClick()
        screenshot("model-dark")
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("navigate-back").performClick()
        screenshot("remote-dark")
    }

    @Test fun invalidStoredModeFallsBackToSystemAfterActivityRecreation() {
        preferences.edit().putString("theme", "unsupported").commit()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-settings").performScrollTo().performClick()
        compose.onNodeWithTag("theme-system").assertIsSelected()
        compose.onNodeWithTag("theme-system").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("theme-system").assertIsSelected()
    }
    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val folder = checkNotNull(compose.activity.getExternalFilesDir(null))
        File(folder, "appearance-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

}
