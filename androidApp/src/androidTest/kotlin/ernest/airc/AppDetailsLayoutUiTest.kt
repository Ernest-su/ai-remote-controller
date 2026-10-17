package ernest.airc

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Narrow-window and large-font fixture; no target operations. */
class AppDetailsLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun narrowLargeTextDetailsStayScrollableInBothThemes() {
        val app = TargetAppInfo("fixture.app", 10, 10001, 1, codePath = "/data/app/fixture", lastUpdateTime = "fixture", system = false)
        val snapshot = AppControlSnapshot("fixture.app", 10, true, 0, false, AppControlCapabilities(true, true, true), listOf(
            AppPermission("android.permission.CAMERA", "相机权限与较长的说明名称", false, true, true),
            AppPermission("android.permission.SYSTEM_ALERT_WINDOW", "悬浮窗", false, false, false, "特殊访问仅供查看，请在目标设备系统设置中管理")))
        var dark by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
                Box(Modifier.width(320.dp).height(480.dp)) { RemoteTheme(darkTheme = dark) {
                    AppDetailsPanel("fixture.app", AppControlState("fixture.app", details = AppControlDetails(app, snapshot)), true,
                        listOf("apps.control_info", "apps.prepare_control", "apps.clear_cache", "apps.uninstall"), false, {}, { _, _ -> }, {}, {}, {}, {})
                } }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            compose.onNodeWithText("清除全部数据").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("app-permission-android.permission.CAMERA").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
            val output = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "app-details-${if (mode) "dark" else "light"}.png")
            output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
