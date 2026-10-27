package ernest.airc

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ScreenPowerUiTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)
    @Test fun screenButtonIsLeftOfPowerAndTogglesAcrossRotation() {
        var on = true
        val writes = CopyOnWriteArrayList<Boolean>()
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/status" -> MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"fixture","protocol":1,"deviceId":"screen-ui-fixture","tools":["screen.power"],"modelConfigured":false}"""))
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(socket: WebSocket, text: String) {
                        val message = Json.parseToJsonElement(text).jsonObject
                        val screen = message["tool"]?.jsonPrimitive?.content == "screen.power"
                        if (screen) message["arguments"]?.jsonObject?.get("on")?.jsonPrimitive?.boolean?.let { on = it; writes += it }
                        socket.send(buildJsonObject {
                            put("id", message.getValue("id")); put("protocol", 1); put("ok", true)
                            put("result", if (screen) """{"on":$on}""" else "")
                        }.toString())
                    }
                })
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        lateinit var model: RemoteViewModel
        try {
            compose.onNodeWithContentDescription("关闭屏幕").assertIsNotEnabled()
            compose.runOnUiThread {
                model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
                model.discovered(DiscoveredService("Screen fixture", "127.0.0.1", server.port, emptyMap()))
            }
            compose.waitUntil(10000) { model.screenPower.state.value == true }
            val screen = compose.onNodeWithContentDescription("关闭屏幕")
            screen.assertIsEnabled().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            assertTrue(screen.fetchSemanticsNode().boundsInRoot.right <= compose.onNodeWithContentDescription("电源").fetchSemanticsNode().boundsInRoot.left)
            screen.performClick()
            compose.waitUntil(5000) { model.screenPower.state.value == false }
            compose.onNodeWithContentDescription("打开屏幕").assertIsEnabled()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val directory = java.io.File(compose.activity.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
            java.io.File(directory, "screen-power.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithContentDescription("打开屏幕").performClick()
            compose.waitUntil(5000) { model.screenPower.state.value == true }
            assertEquals(listOf(false, true), writes.toList())
            compose.runOnUiThread { model.disconnect() }
            compose.waitUntil(5000) { !model.state.value.connected }
            compose.onNodeWithContentDescription("关闭屏幕").assertIsNotEnabled()
        } finally {
            compose.runOnUiThread { ViewModelProvider(compose.activity)[RemoteViewModel::class.java].disconnect() }
            server.shutdown()
            DeviceStore(InstrumentationRegistry.getInstrumentation().targetContext).remove("screen-ui-fixture")
        }
    }
}
