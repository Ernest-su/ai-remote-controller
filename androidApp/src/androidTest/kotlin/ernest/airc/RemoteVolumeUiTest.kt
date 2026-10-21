package ernest.airc

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
class RemoteVolumeUiTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)

    @Test fun realRemoteReadsCurrentVolumeAndTouchDragSetsItThenTracksExternalChanges() {
        val index = AtomicInteger(57)
        val writes = CopyOnWriteArrayList<Int>()
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/status" -> MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"fixture","protocol":1,"deviceId":"volume-ui-fixture","tools":["media.volume"],"modelConfigured":false}"""))
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val message = Json.parseToJsonElement(text).jsonObject
                        val isVolume = message["tool"]?.jsonPrimitive?.content == "media.volume"
                        val requested = message["arguments"]?.jsonObject?.get("percent")?.jsonPrimitive?.int
                        if (isVolume && requested != null) { writes += requested; index.set(requested) }
                        val result = if (isVolume) """{"volumeIndex":${index.get()},"minVolumeIndex":0,"maxVolumeIndex":100,"fixed":false}""" else ""
                        webSocket.send(buildJsonObject {
                            put("id", message.getValue("id")); put("protocol", 1); put("ok", true); put("result", result)
                        }.toString())
                    }
                })
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        lateinit var model: RemoteViewModel
        try {
            compose.runOnUiThread {
                model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
                model.discovered(DiscoveredService("Volume fixture", "127.0.0.1", server.port, emptyMap()))
            }
            compose.waitUntil(10000) { model.state.value.connected && model.remoteVolume.state.value?.index == 57 }
            val slider = compose.onNodeWithTag("remote-volume-slider")
            fun fraction() = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
            slider.assertIsEnabled()
            assertEquals(0.57f, fraction(), 0.001f)
            val label = compose.onNodeWithTag("remote-volume-value")
            label.assertTextEquals("57%")
            slider.performTouchInput {
                down(center)
                moveTo(Offset(width * 0.8f, centerY), delayMillis = 600)
            }
            label.assertTextEquals("${(fraction() * 100).roundToInt()}%")
            assertTrue("Dragging must not submit before release", writes.isEmpty())
            slider.performTouchInput { up() }
            compose.waitUntil(5000) { writes.isNotEmpty() && !model.remoteVolume.setting.value }
            assertTrue("Touch drag must set a higher volume", writes.last() in 65..100)
            compose.waitForIdle()
            assertEquals(index.get() / 100f, fraction(), 0.001f)
            label.assertTextEquals("${index.get()}%")
            index.set(23)
            compose.waitUntil(5000) { model.remoteVolume.state.value?.index == 23 }
            compose.waitForIdle()
            assertEquals(0.23f, fraction(), 0.001f)
            label.assertTextEquals("23%")
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            assertEquals(0.23f, fraction(), 0.001f)
            label.assertTextEquals("23%")
            compose.runOnUiThread { model.disconnect() }
            compose.waitUntil(5000) { !model.state.value.connected }
            slider.assertIsNotEnabled()
            label.assertTextEquals("—")
        } finally {
            compose.runOnUiThread { ViewModelProvider(compose.activity)[RemoteViewModel::class.java].disconnect() }
            server.shutdown()
            DeviceStore(InstrumentationRegistry.getInstrumentation().targetContext).remove("volume-ui-fixture")
        }
    }
}
