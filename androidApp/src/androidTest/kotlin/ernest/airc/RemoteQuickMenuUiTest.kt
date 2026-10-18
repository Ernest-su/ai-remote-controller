package ernest.airc

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class RemoteQuickMenuUiTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)

    @Test fun menuIsVerticalAndAllThreePagesRemainBrowsableOfflineInBothThemes() {
        var dark by mutableStateOf(false)
        compose.runOnUiThread {
            val model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
            model.disconnect()
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    Box(Modifier.width(320.dp).height(640.dp).testTag("remote-menu-layout")) { RemoteTheme(darkTheme = dark) { RemoteApp(model) } }
                }
            }
        }
        val labels = listOf("常用应用", "电视输入", "快捷操作")
        val dialogs = listOf("quick-apps-dialog", "tv-input-dialog", "quick-routines-dialog")
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            labels.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
            compose.onNodeWithTag("remote-more").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("device-status").assertIsDisplayed()
            compose.onNodeWithTag("open-remote-types").assertIsDisplayed()
            capture("header", mode, "remote-menu-layout")
            for (index in labels.indices) {
                compose.onNodeWithTag("remote-more").performClick()
                val rows = labels.indices.map { compose.onNodeWithTag("remote-quick-item-$it").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInWindow }
                assertTrue(rows[0].bottom <= rows[1].top && rows[1].bottom <= rows[2].top)
                capture("popup", mode, "remote-quick-menu")
                compose.onNodeWithText(labels[index]).performClick()
                compose.onNodeWithTag(dialogs[index]).assertExists()
                compose.onNodeWithTag("remote-quick-menu").assertDoesNotExist()
                compose.onNodeWithTag("${dialogs[index]}-close").performClick()
                compose.onNodeWithTag("remote-page").assertIsDisplayed()
            }
        }
        compose.onNodeWithTag("remote-more").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("remote-quick-menu").assertDoesNotExist()
        compose.onNodeWithTag("device-status").performClick()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("remote-more").assertIsDisplayed()
    }
    private fun capture(name: String, dark: Boolean, tag: String) {
        val output = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "remote-menu-$name-${if (dark) "dark" else "light"}.png")
        output.outputStream().use { compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
