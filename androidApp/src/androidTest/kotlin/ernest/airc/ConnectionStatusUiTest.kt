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
import org.junit.*
import org.junit.Assert.*

class ConnectionStatusUiTest {
    val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)
    @Test fun compactStatusesUseTextAndDotsWithoutRecoveryOrPreviewButtons() {
        var dark by mutableStateOf(false)
        var green = 0; var red = 0
        var message by mutableStateOf("正在等待被控端 ADB 响应…")
        compose.setContent { RemoteTheme(darkTheme = dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
                green = RemoteColors.Brand.toArgb(); red = RemoteColors.Warning.toArgb()
                Box(Modifier.width(320.dp)) { ConnectionStatusSummary(true,false,false,false,{},message) }
            }
        } }
        repeat(2) {
            compose.onNodeWithContentDescription("遥控服务，已连接").assertExists()
            compose.onNodeWithContentDescription("ADB，按需自动连接").assertExists()
            compose.onNodeWithText("动态预览").assertDoesNotExist()
            compose.onNodeWithText("未开启").assertDoesNotExist()
            compose.onNodeWithTag("device-connection-status").assertTextEquals(message)
            compose.runOnIdle { message = "被控端 ADB 未响应，3 秒后重试" }
            compose.onNodeWithTag("device-connection-status").assertTextEquals(message)
            for (text in listOf("恢复遥控服务","恢复 ADB","打开预览与重试","遥控、ADB 和预览分别连接；预览关闭不影响普通遥控。")) compose.onNodeWithText(text).assertDoesNotExist()
            fun dot(label: String): Int {
                val bitmap=compose.onNodeWithTag("connection-dot-$label",useUnmergedTree=true).captureToImage().asAndroidBitmap()
                return bitmap.getPixel(bitmap.width/2,bitmap.height/2)
            }
            assertEquals(green,dot("遥控服务"));assertEquals(red,dot("ADB"))
            val directory = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            val image = compose.onNodeWithTag("connection-summary").captureToImage().asAndroidBitmap()
            java.io.File(directory, "connection-summary-${if (dark) "dark" else "light"}.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
            compose.runOnIdle { dark=true }
        }
    }
    @Test fun remoteHomeShowsConnectionProgressAndReturnsToPlaybackAfterCancellation() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val server = okhttp3.mockwebserver.MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
            }
            start()
        }
        DeviceStore(context).remember(RememberedDevice("home-progress-fixture", "Fixture", ConnectionMethod.TCP,
            "127.0.0.1", 1, runtimePort = server.port, lastConnectedAt = 1))
        val owner = androidx.lifecycle.ViewModelStore()
        lateinit var model: RemoteViewModel
        try {
            compose.runOnUiThread {
                model = RemoteViewModel(context.applicationContext as android.app.Application)
                owner.put("home-progress", model)
            }
            compose.setContent { RemoteTheme { RemoteApp(model) } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("remote-connection-progress").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("连接状态").assertIsDisplayed()
            compose.onNodeWithContentDescription("连接中，Fixture，管理设备").assertExists()
            compose.onNodeWithTag("remote-connection-progress").assertTextEquals("连接遥控服务")
            compose.onNodeWithTag("remote-playback").assertDoesNotExist()
            compose.runOnUiThread { model.cancelConnection() }
            compose.onNodeWithTag("remote-connection-progress").assertDoesNotExist()
            compose.onNodeWithTag("remote-playback").assertExists()
            compose.runOnIdle { assertNull(model.state.value.connectingDevice) }
        } finally {
            compose.runOnUiThread { owner.clear() }
            server.shutdown()
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
        compose.onNodeWithContentDescription("连接中，Another device，管理设备").assertExists()
        compose.mainClock.advanceTimeBy(RemoteDimensions.ConnectionLabelIntervalMillis + 100)
        compose.waitUntil(5000) { compose.onAllNodesWithText("Another device").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { name = "Next target" }
        compose.onNodeWithText("Another device").assertDoesNotExist()
        compose.onNodeWithText("连接中").assertExists()
        compose.mainClock.advanceTimeBy(RemoteDimensions.ConnectionLabelIntervalMillis + 100)
        compose.waitUntil(5000) { compose.onAllNodesWithText("Next target").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("device-status").performClick()
        compose.runOnIdle { assertEquals(2, taps) }
    }
}
