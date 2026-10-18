package ernest.airc

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
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
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)
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
        compose.onNodeWithTag("remote-more").performClick()
        compose.onNodeWithText("电视输入").performClick()
        captureLibrary("tv-input-dialog", "tv-input-dialog")
        compose.onNodeWithTag("tv-input-mode-ime").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("tv-input-enable").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("tv-input-draft").performScrollTo().performClick().performTextInput("中文片名")
        compose.onNodeWithTag("tv-input-send").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        captureLibrary("tv-input-dialog", "tv-input-editing")
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "tv-input-keyboard-screen.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val oldVolumeSetting = model.state.value.volumeKeysEnabled
        try {
            // Global state updates must preserve the editor window's resize policy.
            compose.runOnUiThread { model.setVolumeKeysEnabled(!oldVolumeSetting) }
            compose.onNodeWithTag("tv-input-dialog-close").assertIsDisplayed()
        } finally { compose.runOnUiThread { model.setVolumeKeysEnabled(oldVolumeSetting) } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tv-input-mode-ime").assertIsSelected()
        compose.onNodeWithTag("tv-input-draft").assertTextContains("中文片名")
        compose.onNodeWithTag("tv-input-dialog-close").performClick()
        compose.onNodeWithTag("remote-more").performClick()
        compose.onNodeWithText("电视输入").performClick()
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
        val failNextKey = java.util.concurrent.atomic.AtomicBoolean(false)
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
                        val failed = tool == "remote.key" && args["key"]?.jsonPrimitive?.content == "ok" && failNextKey.getAndSet(false)
                        socket.send(buildJsonObject {
                            put("id", message.getValue("id")); put("protocol", 1); put("ok", !failed)
                            if (failed) put("error", "fixture key failed") else put("result", result)
                        }.toString())
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
            compose.onNodeWithTag("remote-more").performClick()
            compose.onNodeWithText("常用应用").performClick()
            compose.waitUntil(10000) { model.state.value.apps.isNotEmpty() }
            compose.onNodeWithTag("app-favorite-fixture.player").assertIsOff().performClick().assertIsOn()
            assertEquals(listOf("fixture.player"), model.state.value.shortcuts.favorites)
            captureLibrary("quick-apps-dialog", "favorites-page")
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
            compose.onNodeWithTag("remote-more").performClick()
            compose.onNodeWithText("快捷操作").performClick()
            compose.onNodeWithContentDescription("新建快捷操作").performClick()
            compose.onNodeWithTag("routine-name").performTextInput("观影组合")
            compose.onNodeWithText("添加步骤").performScrollTo().performClick()
            compose.onNodeWithTag("routine-choose-app").performClick()
            compose.onNodeWithText("收藏测试").performClick()
            compose.onNodeWithText("添加此步骤").performClick()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("routine-name").assertTextContains("观影组合")
            compose.onNodeWithText("收藏测试").assertExists()
            compose.onNodeWithText("添加步骤").performScrollTo().performClick()
            compose.onNodeWithTag("routine-kind-key").performClick()
            compose.onNodeWithTag("routine-key-ok").performClick()
            compose.onNodeWithText("添加此步骤").performClick()
            compose.onNodeWithText("添加步骤").performScrollTo().performClick()
            compose.onNodeWithTag("routine-kind-wait").performClick()
            compose.onNodeWithText("2 秒").performClick()
            compose.onNodeWithText("添加此步骤").performClick()
            compose.onNodeWithTag("routine-step-menu-2").performScrollTo().performClick()
            compose.onNodeWithText("向前移动").performClick()
            compose.onNodeWithText("等待 2 秒").performScrollTo().performClick()
            compose.onNodeWithTag("routine-wait-seconds").assertTextContains("2").performTextReplacement("1.5")
            compose.onNodeWithText("保存步骤").performClick()
            captureLibrary("quick-routines-dialog", "routine-editor-page")
            compose.onNodeWithText("保存组合").performClick()
            assertEquals("观影组合", model.state.value.routines.single().name)
            assertEquals(listOf(RoutineStep("app", "fixture.player"), RoutineStep("wait", "1500"), RoutineStep("key", "ok")), model.state.value.routines.single().steps)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("观影组合").assertExists()
            captureLibrary("quick-routines-dialog", "routines-page")
            compose.onNodeWithContentDescription("运行 观影组合").performClick()
            compose.onNodeWithTag("routine-library").assertExists()
            compose.onNodeWithTag("routine-run-preview").assertDoesNotExist()
            compose.onNodeWithContentDescription("暂停 观影组合").performClick()
            compose.waitUntil(3000) { model.quickRoutines.state.value.paused }
            assertTrue(requests.none { it["key"]?.jsonPrimitive?.content == "ok" })
            compose.activityRule.scenario.recreate()
            compose.onNodeWithContentDescription("继续 观影组合").assertExists().performClick()
            compose.waitUntil(5000) { !model.quickRoutines.state.value.busy }
            assertEquals(1, requests.count { it["key"]?.jsonPrimitive?.content == "ok" })
            compose.onNodeWithContentDescription("更多 观影组合").performClick()
            compose.onNodeWithText("最后一次执行详情").performClick()
            compose.onNodeWithTag("routine-execution-details").assertExists()
            compose.onNodeWithText("执行完成").assertExists()
            captureLibrary("quick-routines-dialog", "routine-details-page")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("routine-execution-details").assertExists()
            compose.onNodeWithTag("quick-routines-dialog-close").performClick()
            failNextKey.set(true)
            compose.onNodeWithContentDescription("运行 观影组合").performClick()
            compose.waitUntil(5000) { !model.quickRoutines.state.value.busy }
            compose.onNodeWithContentDescription("重新执行 观影组合").assertExists()
            assertEquals(2, requests.count { it["key"]?.jsonPrimitive?.content == "ok" })
            captureLibrary("quick-routines-dialog", "routine-error-page")
            compose.onNodeWithContentDescription("更多 观影组合").performClick()
            compose.onNodeWithText("最后一次执行详情").performClick()
            compose.onNodeWithText("执行失败").assertExists()
            compose.onNodeWithText("fixture key failed").assertExists()
            compose.onNodeWithTag("quick-routines-dialog-close").performClick()
            compose.onNodeWithContentDescription("重新执行 观影组合").performClick()
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.waitUntil(3000) { !model.quickRoutines.state.value.busy }
            assertEquals(RoutineStatus.STOPPED, model.quickRoutines.history.value.single { it.deviceId == device }.status)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.onNodeWithContentDescription("运行 观影组合").assertExists()
            assertEquals(2, requests.count { it["key"]?.jsonPrimitive?.content == "ok" })
            compose.onNodeWithContentDescription("更多 观影组合").performClick()
            compose.onNodeWithText("编辑组合").performClick()
            compose.onNodeWithTag("routine-name").performTextReplacement("未保存名称")
            compose.onNodeWithTag("quick-routines-dialog-close").performClick()
            compose.onNodeWithText("继续编辑").performClick()
            compose.onNodeWithTag("routine-name").assertTextContains("未保存名称")
            compose.onNodeWithTag("quick-routines-dialog-close").performClick()
            compose.onNodeWithText("放弃修改").performClick()
            assertEquals("观影组合", model.state.value.routines.single().name)
            compose.onNodeWithContentDescription("更多 观影组合").performClick()
            compose.onNodeWithText("删除组合").performClick()
            compose.onNodeWithText("取消").performClick()
            assertEquals(1, model.state.value.routines.size)
            compose.onNodeWithContentDescription("更多 观影组合").performClick()
            compose.onNodeWithText("删除组合").performClick()
            compose.onNodeWithText("删除", substring = false).performClick()
            assertTrue(model.state.value.routines.isEmpty())
        } finally {
            compose.runOnUiThread { model.setVolumeKeysEnabled(oldVolume); model.disconnect(); model.forget(device) }
            compose.activity.getSharedPreferences("app-shortcuts", 0).edit().remove(device).commit()
            server.shutdown()
        }
    }
    private fun captureLibrary(tag: String, name: String) {
        val output = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "$name.png")
        output.outputStream().use { compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

}
