package ernest.airc

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** Learn a configured target through the real status API, then test the composer offline. */
internal fun configuredOfflineComposer(compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>) {
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/status" -> MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"fixture","protocol":1,"deviceId":"composer-fixture","tools":[],"modelConfigured":true}"""))
            "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})
            else -> MockResponse().setResponseCode(404)
        }
    }
    server.start()
    lateinit var model: RemoteViewModel
    try {
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
            model.discovered(DiscoveredService("Composer fixture", "127.0.0.1", server.port, emptyMap()))
        }
        compose.waitUntil(10_000) { model.state.value.connected && !model.state.value.busy && model.state.value.modelConfigured }
        compose.runOnUiThread { model.disconnect() }
        compose.waitUntil(10_000) { !model.state.value.connected }
    } finally {
        server.shutdown()
        DeviceStore(InstrumentationRegistry.getInstrumentation().targetContext).remove("composer-fixture")
    }
}
