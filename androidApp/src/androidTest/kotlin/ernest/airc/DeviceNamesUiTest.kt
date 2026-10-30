package ernest.airc

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Read-only row fixtures; never scan or connect to external devices. */
class DeviceNamesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun modelsAddressFallbacksAndSavedNamesFitNarrowLargeTextInBothThemes() {
        val state = RemoteUiState(
            history = listOf(RememberedDevice("saved", "Original model", ConnectionMethod.TCP,
                host = "192.0.2.3", lastConnectedAt = 1, displayName = "客厅电视")),
            adbDevices = listOf(DiscoveredAdbDevice("192.0.2.1", model = "Living Room Android TV"),
                DiscoveredAdbDevice("192.0.2.2"), DiscoveredAdbDevice("192.0.2.3", model = "Hardware model")),
        )
        var dark by mutableStateOf(false)
        val connections = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
                RemoteTheme(darkTheme = dark) {
                    Column(Modifier.width(320.dp).height(640.dp).background(RemoteColors.Surface)
                        .verticalScroll(rememberScrollState())) {
                        deviceListEntries(state).forEach { entry ->
                            UnifiedDeviceRow(entry, state, onConnect = { connections += entry.host },
                                onRename = { _, _ -> error("Unexpected rename") }, onForget = { error("Unexpected forget") })
                        }
                    }
                }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            for ((host, name) in listOf("192.0.2.1" to "Living Room Android TV",
                "192.0.2.2" to "Android 设备 · 192.0.2.2", "192.0.2.3" to "客厅电视")) {
                compose.onNodeWithText(name).performScrollTo().assertIsDisplayed()
                val connect = compose.onNode(hasText("连接") and hasAnyAncestor(hasTestTag("device-row-$host")))
                connect.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            }
            compose.onNodeWithText("Hardware model").assertDoesNotExist()
            compose.onNodeWithContentDescription("管理 客厅电视").performScrollTo().performClick()
            val rename = compose.onNodeWithText("修改显示名")
            val remove = compose.onNodeWithText("从已保存记录移除")
            rename.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            remove.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val renameBounds = rename.fetchSemanticsNode().boundsInRoot
            val removeBounds = remove.fetchSemanticsNode().boundsInRoot
            assertEquals(renameBounds.top, removeBounds.top, 1f)
            assertEquals(renameBounds.bottom, removeBounds.bottom, 1f)
            org.junit.Assert.assertTrue(renameBounds.right <= removeBounds.left)
            compose.onNodeWithText("忘记已保存记录").assertDoesNotExist()
            val output = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
                "device-names-${if (mode) "dark" else "light"}.png")
            output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithContentDescription("管理 客厅电视").performScrollTo().performClick()
        }
        compose.runOnIdle { assertEquals(List(2) { listOf("192.0.2.1", "192.0.2.2", "192.0.2.3") }.flatten(), connections) }
    }
}
