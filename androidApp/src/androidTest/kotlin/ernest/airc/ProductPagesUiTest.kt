package ernest.airc

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline visual fixtures; never deploy to or operate an external target. */
@RunWith(AndroidJUnit4::class)
class ProductPagesUiTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)

    @Test fun allFeaturePagesRemainBrowsableInBothThemesAndLargeText() {
        val pages = listOf("apps" to "apps-page", "text" to "text-page", "model" to "model-page",
            "service" to "service-page", "permissions" to "permissions-page", "device_info" to "device-info-page",
            "results" to "results-page", "help" to "help-page", "screenshot" to "screenshot-page",
            "files" to "files-page", "schedules" to "schedule-page", "settings" to "settings-page", "about" to "about-page")
        for (dark in listOf(false, true)) {
            compose.runOnUiThread {
                val model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
                compose.activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                        Box(Modifier.width(320.dp).fillMaxHeight()) {
                            RemoteTheme(darkTheme = dark) { RemoteApp(model) }
                        }
                    }
                }
            }
            compose.onNodeWithTag("remote-page").assertIsDisplayed()
            capture("remote-$dark")
            compose.onNodeWithTag("open-menu").performClick()
            val first = compose.onNodeWithTag("feature-apps").fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithTag("feature-text").fetchSemanticsNode().boundsInRoot
            assertEquals(first.height, second.height, 1f)
            compose.onNodeWithTag("feature-apps").assertHeightIsAtLeast(104.dp)
            capture("menu-$dark")
            for ((feature, page) in pages) {
                compose.onNodeWithTag("feature-$feature").performScrollTo().performClick()
                compose.onNodeWithTag(page).assertIsDisplayed()
                capture("$feature-$dark")
                compose.onNodeWithTag("device-status").performClick()
                compose.onNodeWithTag("devices-page").assertIsDisplayed()
                compose.onNodeWithTag("navigate-back").performClick()
                compose.onNodeWithTag(page).assertIsDisplayed()
                compose.onNodeWithTag("navigate-back").performClick()
            }
            compose.onNodeWithTag("navigate-back").performClick()
            compose.onNodeWithTag("device-status").performClick()
            capture("devices-$dark")
            compose.onNodeWithTag("navigate-back").performClick()
            compose.onNodeWithTag("open-remote-types").performClick()
            capture("remote-types-$dark")
            for (type in listOf("touchpad", "keypad", "media_control")) {
                compose.onNodeWithTag("remote-type-$type").performScrollTo().performClick()
                capture("$type-$dark")
                compose.onNodeWithTag("navigate-back").performClick()
            }
            compose.onNodeWithTag("navigate-back").performClick()
        }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
