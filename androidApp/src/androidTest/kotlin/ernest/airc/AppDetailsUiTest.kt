package ernest.airc

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local protocol fixture; actual app mutations are covered by the UUID target fixture. */
@RunWith(AndroidJUnit4::class)
class AppDetailsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun applicationDetailsRestoreSelectionAndPermissionsRequireConfirmation() {
        val app = TargetAppInfo("fixture.app", 0, 10001, 1, codePath = "/data/app/fixture", lastUpdateTime = "fixture", system = false)
        val snapshot = AppControlSnapshot("fixture.app", 0, true, 0, false, AppControlCapabilities(true, true, true), listOf(
            AppPermission("android.permission.CAMERA", "相机", false, true, true),
            AppPermission("android.permission.SYSTEM_ALERT_WINDOW", "悬浮窗", false, false, false, "特殊访问仅供查看")))
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/status" -> MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"fixture","protocol":1,"deviceId":"app-details-fixture","tools":["apps.list","apps.control_info","apps.prepare_control","apps.clear_cache","apps.uninstall"]}"""))
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val message = Json.parseToJsonElement(text).jsonObject
                        val result = when (message["tool"]?.jsonPrimitive?.content) {
                            "apps.list" -> """{"apps":[{"package":"fixture.app","label":"测试应用","system":false}]}"""
                            "apps.control_info" -> Json.encodeToString(AppControlDetails(app, snapshot))
                            "apps.prepare_control" -> {
                                val args = message.getValue("arguments").jsonObject
                                Json.encodeToString(AppControlProposal("proposal", "app_control", app, args.getValue("action").jsonPrimitive.content,
                                    args["permission"]?.jsonPrimitive?.content, true, false, snapshot))
                            }
                            else -> "{}"
                        }
                        webSocket.send(buildJsonObject { put("id", message.getValue("id")); put("protocol", 1); put("ok", true); put("result", result) }.toString())
                    }
                })
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        lateinit var model: RemoteViewModel
        var initialized = false
        try {
            compose.runOnUiThread {
                initialized = true
                model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
                model.discovered(DiscoveredService("App fixture", "127.0.0.1", server.port, emptyMap()))
            }
            compose.waitUntil(10_000) { model.state.value.connected && !model.state.value.busy }
            compose.onNodeWithTag("open-menu").performClick()
            compose.onNodeWithTag("feature-apps").performClick()
            compose.waitUntil(10_000) { model.state.value.apps.isNotEmpty() }
            compose.onNodeWithText("测试应用").performClick()
            compose.onNodeWithTag("app-details-page").assertIsDisplayed()
            compose.waitUntil(10_000) { model.appControl.state.value.details != null }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("app-details-page").assertIsDisplayed()
            compose.onNodeWithTag("device-status").performClick()
            compose.onNodeWithTag("navigate-back").performClick()
            compose.onNodeWithTag("app-details-page").assertIsDisplayed()
            compose.waitUntil(10_000) { !model.appControl.state.value.busy }
            compose.onNodeWithTag("app-permission-android.permission.CAMERA").performScrollTo().assertIsOff().performClick()
            compose.onNodeWithTag("app-control-confirm").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            compose.waitUntil(10_000) { !model.appControl.state.value.busy }
            compose.onNodeWithTag("app-permission-android.permission.CAMERA").assertIsOff()
            compose.onNodeWithTag("app-permission-android.permission.SYSTEM_ALERT_WINDOW").assertDoesNotExist()
            compose.runOnUiThread { model.disconnect() }
            compose.waitUntil(10_000) { !model.state.value.connected }
            compose.onNodeWithText("刷新应用详情").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("navigate-back").performClick()
            compose.onNodeWithTag("apps-page").assertIsDisplayed()
        } finally {
            if (initialized) compose.runOnUiThread { model.disconnect() }
            server.shutdown()
            DeviceStore(InstrumentationRegistry.getInstrumentation().targetContext).remove("app-details-fixture")
        }
    }
}
