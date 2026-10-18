package ernest.airc

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class MobileConvenienceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun model(): RemoteViewModel {
        lateinit var result: RemoteViewModel
        compose.runOnUiThread { result = ViewModelProvider(compose.activity)[RemoteViewModel::class.java] }
        compose.runOnUiThread { result.disconnect() }
        compose.waitUntil(10000) { !result.state.value.busy }
        return result
    }
    @Test fun independentTelevisionInputNeedsNoModelAndRetainsOnlyMemoryDraft() {
        val model = model()
        compose.runOnUiThread { model.disconnect() }
        compose.onNodeWithText("电视输入").performScrollTo().performClick()
        compose.onNodeWithTag("tv-input-draft").performTextInput("中文片名")
        compose.onNodeWithText("发送到电视").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tv-input-draft").assertTextContains("中文片名")
        compose.onNodeWithTag("tv-input-dialog-close").performClick()
        compose.onNodeWithText("电视输入").performScrollTo().performClick()
        compose.onNodeWithTag("tv-input-draft").assertTextContains("中文片名")
        compose.onNodeWithText("清空草稿").performScrollTo().performClick()
        assertEquals("", model.tvInput.state.value.draft)
    }
    @Test fun sharedUrlRequiresExplicitActionAndReusesDownloadDirectoryPicker() {
        val model = model()
        compose.runOnUiThread {
            model.disconnect()
            model.receiveShare(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.com/video.mp4"))
        }
        compose.onNodeWithTag("incoming-share-dialog").assertIsDisplayed()
        assertEquals("idle", model.urlDownloads.state.value.phase)
        compose.onNodeWithText("用此地址下载到电视").performClick()
        compose.onNodeWithTag("url-download-address").assertTextContains("https://example.com/video.mp4")
        compose.onNodeWithText("开始下载").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("选择保存目录").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("shared-download-dialog-close").performClick()
        compose.onNodeWithContentDescription("待处理的分享").performClick()
        compose.onNodeWithText("移除分享内容").performScrollTo().performClick()
        assertNull(model.incomingShare.value)
    }
    @Test fun shareParserRejectsFileUrisAndMultipleFilesAndReadsContentMetadata() = kotlinx.coroutines.runBlocking {
        assertNotNull(incomingShare(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///sdcard/test")), 1)?.error)
        assertNotNull(incomingShare(Intent(Intent.ACTION_SEND_MULTIPLE), 2)?.error)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.cacheDir, "screenshots").apply { mkdirs() }
        val file = java.io.File(directory, "share-fixture-${UUID.randomUUID()}.txt").apply { writeText("fixture") }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.screenshots", file)
        try {
            val share = incomingShare(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri), 3)
            val source = sharedUploadSource(context, checkNotNull(share?.uri))
            assertEquals(file.name, source.name)
            assertEquals(7L, source.size)
            assertEquals("fixture", source.open().use { it.readBytes().toString(Charsets.UTF_8) })
        } finally { file.delete() }
        assertNull(incomingShare(Intent(Intent.ACTION_VIEW), 4))
    }
    @Test fun favoritesPersistAndHardwareVolumeIsRestrictedToRemotePages() {
        val model = model()
        val device = "mobile-fixture-${UUID.randomUUID()}"
        val requests = CopyOnWriteArrayList<JsonObject>()
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/status" -> MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"fixture","protocol":1,"deviceId":"$device","tools":["apps.list","apps.open","remote.key"]}"""))
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(socket: WebSocket, text: String) {
                        val message = Json.parseToJsonElement(text).jsonObject
                        val args = message["arguments"]?.jsonObject ?: buildJsonObject { }
                        val tool = message["tool"]?.jsonPrimitive?.content
                        val result = when (tool) {
                            "apps.list" -> """{"apps":[{"package":"fixture.player","label":"收藏测试","system":false}]}"""
                            "remote.key" -> {
                                requests.add(args)
                                buildJsonObject {
                                    put("operation", args["action"] ?: JsonPrimitive("key")); put("pressId", args["pressId"] ?: JsonNull)
                                    put("submitted", true); put("released", args["action"]?.jsonPrimitive?.content in listOf(null, "up", "cancel"))
                                }.toString()
                            }
                            else -> "fixture"
                        }
                        socket.send(buildJsonObject { put("id", message.getValue("id")); put("protocol", 1); put("ok", true); put("result", result) }.toString())
                    }
                })
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val oldVolume = model.state.value.volumeKeysEnabled
        try {
            compose.runOnUiThread { model.discovered(DiscoveredService("fixture", "127.0.0.1", server.port, emptyMap())) }
            compose.waitUntil(10000) { model.state.value.connected && !model.state.value.busy }
            compose.onNodeWithText("常用应用").performScrollTo().performClick()
            compose.waitUntil(10000) { model.state.value.apps.isNotEmpty() }
            compose.onNodeWithText("管理收藏与顺序").performClick()
            compose.onNodeWithText("收藏 收藏测试").performScrollTo().performClick()
            assertEquals(listOf("fixture.player"), model.state.value.shortcuts.favorites)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("收藏测试").assertExists()
            compose.onNodeWithTag("quick-apps-dialog-close").performClick()
            compose.runOnUiThread {
                model.setVolumeKeysEnabled(true)
                assertTrue(compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
                assertTrue(compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)))
            }
            compose.waitUntil(5000) { requests.isNotEmpty() }
            compose.onNodeWithTag("open-menu").performClick()
            compose.onNodeWithTag("feature-menu").assertIsDisplayed()
            compose.runOnUiThread {
                assertFalse(model.hardwareVolume(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
            }
            assertTrue(requests.none { it["key"]?.jsonPrimitive?.content == "volume_down" })
            compose.onNodeWithTag("navigate-back").performClick()
            compose.onNodeWithText("快捷操作").performScrollTo().performClick()
            compose.onNodeWithText("名称").performTextInput("观影组合")
            compose.onNodeWithText("遥控按键").performScrollTo().performClick()
            compose.onNodeWithText("确认").performScrollTo().performClick()
            compose.onNodeWithText("添加步骤").performScrollTo().performClick()
            compose.onNodeWithText("保存组合").performScrollTo().performClick()
            assertEquals("观影组合", model.state.value.routines.single().name)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("观影组合").assertExists()
            compose.onNodeWithText("运行").performScrollTo().performClick()
            compose.onNodeWithText("取消执行").performScrollTo().performClick()
            assertTrue(requests.none { it["key"]?.jsonPrimitive?.content == "ok" })
        } finally {
            compose.runOnUiThread { model.setVolumeKeysEnabled(oldVolume); model.disconnect(); model.forget(device) }
            compose.activity.getSharedPreferences("app-shortcuts", 0).edit().remove(device).commit()
            server.shutdown()
        }
    }
}
