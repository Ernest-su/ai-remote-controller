package ernest.airc

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** App-private Keystore and local API/runtime fixtures; no production credential or remote target. */
@RunWith(AndroidJUnit4::class)
class ModelProfilesUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val isolatedProfiles = EncryptedProfilesIsolation()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(isolatedProfiles).around(compose)

    private fun renderedKeyContains(value: String): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("model-api-key").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.any { it.layoutInput.text.text.contains(value) }
    }

    @Test fun encryptedProfilesCacheSwitchVisibilityAndManualSync() {
        val calls = CopyOnWriteArrayList<RuntimeRequest>()
        val clears = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/status" -> MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"0.1.0","protocol":1,"deviceId":"model-profile-fixture","tools":[]}"""))
                "/v1/models" -> MockResponse().setBody("""{"data":[{"id":"Model-B"},{"id":"Model-A"}]}""")
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = Json.decodeFromString<RuntimeRequest>(text)
                        calls.add(request)
                        val accepted = request.type != "clear_model" || clears.incrementAndGet() > 1
                        webSocket.send(buildJsonObject {
                            put("id", request.id); put("protocol", 1); put("ok", accepted); put("result", "")
                            if (!accepted) put("error", "fixture clear failed")
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
            model.discovered(DiscoveredService("Model fixture", "127.0.0.1", server.port, emptyMap()))
        }
        val url = server.url("/v1").toString()
        try {
            compose.waitUntil(10_000) { model.state.value.connected && !model.state.value.busy }
            compose.onNodeWithTag("open-menu").performClick()
            compose.onNodeWithTag("feature-model").performClick()
            compose.onNodeWithTag("model-api-url").performScrollTo().performTextReplacement(url)
            compose.onNodeWithTag("model-api-key").performScrollTo().performTextReplacement("fixture-api-key")
            compose.onNodeWithTag("fetch-models").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.profiles.state.value.draft.models.size == 2 }
            compose.onNodeWithText("从缓存列表选择模型").performScrollTo().performClick()
            compose.onNodeWithText("Model-B").performClick()
            compose.onNodeWithTag("model-api-key").performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            compose.onNodeWithTag("model-key-visibility").performClick()
            assertTrue(renderedKeyContains("fixture-api-key"))
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("model-api-key").performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            assertFalse(renderedKeyContains("fixture-api-key"))
            assertEquals("Model-B", model.profiles.state.value.draft.model)
            assertFalse(calls.any { it.type == "configure" })
            compose.onNodeWithText("更新到目标设备").performScrollTo().assertIsEnabled().performClick()
            try { compose.waitUntil(10_000) { calls.count { it.type == "configure" } == 1 && !model.profiles.state.value.syncing } }
            catch (error: Throwable) { throw AssertionError("connected=${model.state.value.connected}, busy=${model.state.value.busy}, syncing=${model.profiles.state.value.syncing}, message=${model.profiles.state.value.message}, requestTypes=${calls.map { it.type }}", error) }
            val request = calls.single { it.type == "configure" }
            assertEquals("\"Model-B\"", request.arguments["model"].toString())
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val restored = EncryptedModelProfileStore(context).read()
            assertEquals("fixture-api-key", restored.profiles.single().apiKey)
            assertEquals(listOf("Model-A", "Model-B"), restored.profiles.single().models)
            assertFalse(context.getSharedPreferences("model-profiles", 0).all.toString().contains("fixture-api-key"))
            compose.onNodeWithText("新增配置").performScrollTo().performClick()
            compose.onNodeWithTag("model-api-url").performScrollTo().performTextReplacement("https://second.example/v1")
            compose.onNodeWithTag("model-api-key").performScrollTo().performTextReplacement("second-fixture-key")
            compose.onNodeWithTag("model-name").performScrollTo().performTextReplacement("manual-model")
            compose.onNodeWithText("保存到 App").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.profiles.state.value.profiles.size == 2 }
            compose.onNodeWithTag("model-profile-$url").performScrollTo().performClick()
            assertEquals("fixture-api-key", model.profiles.state.value.draft.apiKey)
            assertEquals("Model-B", model.profiles.state.value.draft.model)
            compose.onNodeWithTag("delete-model-profile-$url").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.profiles.state.value.profiles.size == 1 }
            assertEquals(1, calls.count { it.type == "configure" })
            compose.onNodeWithText("新增配置").performScrollTo().performClick()
            assertTrue(model.profiles.state.value.draft.apiKey.isEmpty())
            val saved = EncryptedModelProfileStore(context).read().profiles
            compose.onNodeWithText("更新到目标设备").assertDoesNotExist()
            compose.onNodeWithTag("clear-target-model").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10000) { clears.get() == 1 && !model.profiles.state.value.syncing }
            assertTrue(model.state.value.modelConfigured)
            compose.onNodeWithText("清除目标设备模型配置").assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(10000) { clears.get() == 2 && !model.profiles.state.value.syncing }
            assertFalse(model.state.value.modelConfigured)
            compose.onNodeWithText("更新到目标设备").assertIsDisplayed().assertIsNotEnabled()
            assertEquals(saved, EncryptedModelProfileStore(context).read().profiles)
        } finally {
            compose.runOnUiThread { model.disconnect() }
            compose.waitUntil(10_000) { !model.state.value.connected }
            server.shutdown()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            DeviceStore(context).remove("model-profile-fixture")
        }
    }
}
