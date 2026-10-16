package ernest.airc

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local protocol fixture only; does not prove Android command support on the target. */
@RunWith(AndroidJUnit4::class)
class InputToolsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun tapSwipeLongPressAndKeypadUseDirectTools() {
        val calls = java.util.concurrent.CopyOnWriteArrayList<RuntimeRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/status" -> MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"0.1.0","protocol":1,"deviceId":"input-tools-fixture","tools":["remote.key","remote.tap","remote.swipe","remote.long_press"]}"""))
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = Json.decodeFromString<RuntimeRequest>(text)
                        calls.add(request)
                        val result = ""
                        webSocket.send(buildJsonObject {
                            put("id", request.id); put("protocol", 1); put("ok", true); put("result", result)
                        }.toString())
                    }
                })
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        lateinit var model: RemoteViewModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
            model.discovered(DiscoveredService("Info fixture", "127.0.0.1", server.port, emptyMap()))
        }
        try {
            compose.waitUntil(10_000) { model.state.value.connected && !model.state.value.busy }
            compose.onNodeWithTag("open-remote-types").performClick()
            compose.onNodeWithTag("remote-type-touchpad").performScrollTo().performClick()
            compose.onNodeWithTag("touchpad-area").performScrollTo().performTouchInput { click(center) }
            compose.waitUntil(10_000) { calls.any { it.tool == "remote.tap" } }
            compose.waitUntil(10_000) { !model.state.value.touchBusy }
            compose.onNodeWithTag("touchpad-area").performTouchInput { doubleClick(center) }
            compose.waitUntil(10_000) { calls.any { it.tool == "remote.tap" && it.arguments["count"]?.toString() == "2" } }
            compose.waitUntil(10_000) { !model.state.value.touchBusy }
            compose.onNodeWithTag("touchpad-area").performTouchInput { swipeLeft() }
            compose.waitUntil(10_000) { calls.any { it.tool == "remote.swipe" } }
            compose.waitUntil(10_000) { !model.state.value.touchBusy }
            compose.onNodeWithText("长按屏幕中心").performScrollTo().performClick()
            compose.waitUntil(10_000) { calls.any { it.tool == "remote.long_press" } }
            compose.waitUntil(10_000) { !model.state.value.touchBusy }
            compose.onNodeWithTag("navigate-back").performClick()
            compose.onNodeWithTag("remote-type-keypad").performScrollTo().performClick()
            compose.onNodeWithTag("keypad-7").performClick()
            compose.waitUntil(10_000) { calls.any { it.tool == "remote.key" && it.arguments.toString().contains("7") } }
            org.junit.Assert.assertFalse(calls.any { it.type == "text" })
            compose.runOnUiThread { model.disconnect() }
            compose.waitUntil(10_000) { !model.state.value.connected }
            compose.onNodeWithTag("keypad-7").assertIsNotEnabled()
        } finally {
            compose.runOnUiThread { model.disconnect() }
            compose.waitUntil(10_000) { !model.state.value.connected }
            server.shutdown()
            DeviceStore(InstrumentationRegistry.getInstrumentation().targetContext).remove("input-tools-fixture")
        }
    }
}
