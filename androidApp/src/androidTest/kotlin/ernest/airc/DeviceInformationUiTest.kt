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
class DeviceInformationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun refreshDisplaysPartialInfoAndDisconnectClearsIt() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/status" -> MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"0.1.0","protocol":1,"deviceId":"device-info-fixture","tools":["device.info"]}"""))
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = Json.decodeFromString<RuntimeRequest>(text)
                        val result = if (request.tool == "device.info") """{"model":"Fixture TV","sdk":34,"screen":{"width":1920,"height":1080}}""" else ""
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
            compose.onNodeWithTag("open-menu").performClick()
            compose.onNodeWithTag("feature-device_info").performScrollTo().performClick()
            compose.onNodeWithText("刷新设备信息").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.state.value.deviceInfo != null }
            compose.onNodeWithText("Fixture TV").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("1920 × 1080 px").performScrollTo().assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Fixture TV").performScrollTo().assertIsDisplayed()
            compose.runOnUiThread { model.disconnect() }
            compose.waitUntil(10_000) { !model.state.value.connected && model.state.value.deviceInfo == null }
            compose.onNodeWithText("Fixture TV").assertDoesNotExist()
            compose.onNodeWithText("刷新设备信息").performScrollTo().assertIsNotEnabled()
        } finally {
            compose.runOnUiThread { model.disconnect() }
            compose.waitUntil(10_000) { !model.state.value.connected }
            server.shutdown()
            DeviceStore(InstrumentationRegistry.getInstrumentation().targetContext).remove("device-info-fixture")
        }
    }
}
