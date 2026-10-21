package ernest.airc

import android.content.res.Configuration
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteLayoutUiTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)

    @Test fun systemBarIconsMatchEffectiveThemeAfterRecreation() {
        fun verify() {
            compose.runOnUiThread {
                val activity = compose.activity
                val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                val stored = activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", null)
                val systemDark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                val lightIconsBackground = !ThemeMode.fromStored(stored).isDark(systemDark)
                assertEquals("Status icons must match the effective appearance", lightIconsBackground, controller.isAppearanceLightStatusBars)
                assertEquals("Navigation icons must match the effective appearance", lightIconsBackground, controller.isAppearanceLightNavigationBars)
            }
        }
        verify()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        verify()
    }

    @Test fun realKeyboardKeepsComposerNearItsTopAndClosingRestoresLayout() {
        configuredOfflineComposer(compose)
        val activity = compose.activity
        var windowHeight = 0
        compose.runOnUiThread { windowHeight = activity.window.decorView.height }
        val density = activity.resources.displayMetrics.density
        val initialBottom = compose.onNodeWithTag("remote-command-input").fetchSemanticsNode().boundsInWindow.bottom
        compose.onNodeWithTag("remote-command-input").performClick()
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
        fun assertKeyboardGap() {
            val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)!!
            val keyboardTop = windowHeight - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bottom = compose.onNodeWithTag("remote-command-input").fetchSemanticsNode().boundsInWindow.bottom
            val gap = (keyboardTop - bottom) / density
            assertTrue("Input-to-keyboard gap was $gap dp", gap in -1f..12f)
            compose.onNodeWithTag("remote-send-command").assertIsDisplayed()
        }
        assertKeyboardGap()
        compose.onNodeWithTag("remote-command-input").performTextInput("打开设置")
        assertKeyboardGap()
        compose.runOnUiThread {
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        compose.waitForIdle()
        compose.onNodeWithTag("remote-command-input").assertTextContains("打开设置")
        val restoredBottom = compose.onNodeWithTag("remote-command-input").fetchSemanticsNode().boundsInWindow.bottom
        assertEquals(initialBottom, restoredBottom, density)
    }

    @Test fun realKeyboardWithNarrowLayoutAndLargeTextStillKeepsSmallGap() {
        configuredOfflineComposer(compose)
        compose.runOnUiThread {
            val model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
            compose.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.5f)) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) { RemoteTheme { RemoteApp(model) } }
                }
            }
        }
        val activity = compose.activity
        val density = activity.resources.displayMetrics.density
        var windowHeight = 0
        compose.runOnUiThread { windowHeight = activity.window.decorView.height }
        compose.onNodeWithTag("remote-command-input").performClick()
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithTag("remote-command-input").performTextInput("第一行\n第二行\n第三行")
        compose.waitForIdle()
        val keyboardTop = windowHeight - ViewCompat.getRootWindowInsets(activity.window.decorView)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val bottom = compose.onNodeWithTag("remote-command-input").fetchSemanticsNode().boundsInWindow.bottom
        val gap = (keyboardTop - bottom) / density
        assertTrue("Large-text keyboard gap was $gap dp", gap in -1f..12f)
        compose.onNodeWithTag("remote-send-command").assertIsDisplayed().assertWidthIsEqualTo(48.dp)
    }

    @Test fun shortNarrowWindowAndLargeTextKeepReferenceKeySizesAndNavigationAccessible() {
        val dark = mutableStateOf(false)
        configuredOfflineComposer(compose)
        compose.runOnUiThread {
            val model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
            compose.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.5f)) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        RemoteTheme(darkTheme = dark.value) { RemoteApp(model) }
                    }
                }
            }
        }
        compose.onNodeWithTag("remote-volume-slider").assertIsDisplayed().assertIsNotEnabled()
        val volumeNodes = listOf(
            compose.onNodeWithContentDescription("静音"),
            compose.onNodeWithContentDescription("音量 −"),
            compose.onNodeWithTag("remote-volume-slider"),
            compose.onNodeWithContentDescription("音量 +"),
        ).map { it.fetchSemanticsNode().boundsInRoot }
        volumeNodes.zipWithNext().forEach { (left, right) ->
            assertTrue("Volume controls must be ordered without overlap: $volumeNodes", left.right <= right.left + 1f)
            assertEquals(left.center.y, right.center.y, 1f)
        }
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark.value = theme }
            compose.onNodeWithTag("remote-volume-slider").assertIsDisplayed()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val directory = java.io.File(compose.activity.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
            java.io.File(directory, "remote-volume-$theme.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.runOnIdle { dark.value = false }
        compose.onNodeWithTag("remote-dpad").performScrollTo().assertWidthIsEqualTo(220.dp)
        compose.onNodeWithTag("remote-command-input").performTextInput("打开设置")
        // This case checks the fixed short viewport; the two IME cases above cover the keyboard.
        // Text input can asynchronously open IME, so finish that transition before navigation.
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            compose.activity.currentFocus?.clearFocus()
            WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        compose.waitForIdle()
        compose.onNodeWithTag("remote-send-command").assertIsDisplayed().assertWidthIsEqualTo(48.dp).assertIsNotEnabled()
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-model").performScrollTo().performClick()
        compose.onNodeWithTag("device-status").performClick()
        compose.onNodeWithTag("connection-expand").performScrollTo().performClick()
        compose.onNodeWithText("目标设备 IP / 主机名").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("model-page").assertIsDisplayed()
    }
}
