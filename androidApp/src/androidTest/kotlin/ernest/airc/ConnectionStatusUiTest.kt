package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ernest.ascrcpy.scrcpy.ScrcpyState
import org.junit.*
import org.junit.Assert.*

class ConnectionStatusUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun compactStatusesUseTextAndDotsWithoutRecoveryOrPreviewButtons() {
        var dark by mutableStateOf(false)
        var green = 0; var red = 0
        compose.setContent { RemoteTheme(darkTheme = dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
                green = RemoteColors.Brand.toArgb(); red = RemoteColors.Warning.toArgb()
                Box(Modifier.width(320.dp)) { ConnectionStatusSummary(true,false,false,ScrcpyState.Idle,false,{}) }
            }
        } }
        repeat(2) {
            compose.onNodeWithContentDescription("遥控服务，已连接").assertExists()
            compose.onNodeWithContentDescription("ADB，按需自动连接").assertExists()
            compose.onNodeWithContentDescription("动态预览，未开启").assertExists()
            for (text in listOf("恢复遥控服务","恢复 ADB","打开预览与重试","遥控、ADB 和预览分别连接；预览关闭不影响普通遥控。")) compose.onNodeWithText(text).assertDoesNotExist()
            fun dot(label: String): Int {
                val bitmap=compose.onNodeWithTag("connection-dot-$label",useUnmergedTree=true).captureToImage().asAndroidBitmap()
                return bitmap.getPixel(bitmap.width/2,bitmap.height/2)
            }
            assertEquals(green,dot("遥控服务"));assertEquals(red,dot("ADB"));assertEquals(red,dot("动态预览"))
            compose.runOnIdle { dark=true }
        }
    }
    @Test fun headerAlternatesNameAndStatusAndStaysClickable() {
        var connected by mutableStateOf(true)
        var name by mutableStateOf("YT3002")
        var taps=0
        compose.setContent { RemoteTheme { ConnectionBadge(connected,!connected,name) { taps++ } } }
        compose.onNodeWithText("已连接").assertExists()
        compose.mainClock.advanceTimeBy(RemoteDimensions.ConnectionLabelIntervalMillis + 100)
        compose.waitUntil(5000) { compose.onAllNodesWithText("YT3002").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("device-status").performClick()
        compose.runOnIdle { assertEquals(1,taps); name="Another device" }
        compose.onNodeWithText("YT3002").assertDoesNotExist()
        compose.onNodeWithText("已连接").assertExists()
        compose.runOnIdle { connected=false }
        compose.onNodeWithText("连接中").assertExists()
        compose.onNodeWithText("Another device").assertDoesNotExist()
    }
}
