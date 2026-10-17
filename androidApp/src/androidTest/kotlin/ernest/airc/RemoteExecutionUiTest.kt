package ernest.airc

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Local WS fixture verifies text submission and feedback/navigation and captures review images. */
@RunWith(AndroidJUnit4::class)
class RemoteExecutionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun withPreview(adbPort: Int? = null, modelConfigured: Boolean = true, action: (RemoteViewModel, List<RuntimeRequest>) -> Unit) {
        val types = CopyOnWriteArrayList<RuntimeRequest>()
        val languages = CopyOnWriteArrayList<String?>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                languages.add(request.getHeader("Accept-Language"))
                return when (request.path) {
                    "/status" -> MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"0.1.0","protocol":1,"deviceId":"ui-preview-fixture","name":"客厅电视","tools":[],"modelConfigured":$modelConfigured,"uid":2000}"""))
                    "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val message = Json.parseToJsonElement(text).jsonObject
                            types.add(Json.decodeFromString<RuntimeRequest>(text))
                            val result = if (message.getValue("type").jsonPrimitive.content == "text") "文字指令已执行" else ""
                            webSocket.send("""{"id":${message.getValue("id")},"protocol":1,"ok":true,"result":"$result"}""")
                        }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        lateinit var model: RemoteViewModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
            model.discovered(DiscoveredService("UI preview", "127.0.0.1", server.port, emptyMap()), adbPort)
        }
        try {
            compose.waitUntil(10_000) { model.state.value.connected && !model.state.value.busy }
            val tag = compose.activity.resources.configuration.locales[0].language
            assertEquals(listOf(if (tag == "zh") "zh" else "en", if (tag == "zh") "zh" else "en"), languages.take(2))
            action(model, types)
        } finally {
            compose.runOnUiThread { model.disconnect() }
            compose.waitUntil(10_000) { !model.state.value.connected }
            server.shutdown()
            DeviceStore(InstrumentationRegistry.getInstrumentation().targetContext).remove("ui-preview-fixture")
        }
    }
    @Test fun unconfiguredStatusHidesComposerUntilTargetConfigurationSucceeds() = withPreview(modelConfigured = false) { model, requests ->
        compose.onNodeWithTag("remote-command-input").assertDoesNotExist()
        compose.onNodeWithTag("remote-send-command").assertDoesNotExist()
        compose.onNodeWithTag("remote-model-required").assertIsDisplayed().assertHasNoClickAction()
        compose.runOnUiThread { model.configure("https://fixture.invalid/v1", "fixture-key", "fixture-model") }
        compose.waitUntil(10_000) { model.state.value.modelConfigured && !model.state.value.busy }
        compose.onNodeWithTag("remote-model-required").assertDoesNotExist()
        compose.onNodeWithTag("remote-command-input").assertIsDisplayed()
        compose.onNodeWithTag("remote-send-command").assertIsDisplayed().assertIsNotEnabled()
        assertFalse(requests.any { it.type == "text" })
    }

    @Test fun everyPreviewVisitAutomaticallyStartsButLeavingStopsCapture() = withPreview { model, _ ->
        repeat(2) {
            compose.onNodeWithTag("open-menu").let { menu -> if(it==0) menu.performClick() }
            compose.onNodeWithTag("feature-screenshot").performScrollTo().performClick()
            // This runtime-only fixture has no saved ADB port: attempting preview must fail once.
            try {
                compose.waitUntil(10000) { model.ascrcpyPreview.state.value is ernest.ascrcpy.scrcpy.ScrcpyState.Failed }
            } catch(error: Exception) {
                throw AssertionError("Visit $it: preview=${model.ascrcpyPreview.state.value}, connected=${model.state.value.connected}, busy=${model.state.value.busy}",error)
            }
            compose.onNodeWithTag("navigate-back").performClick()
            compose.waitForIdle()
            assertEquals(ernest.ascrcpy.scrcpy.ScrcpyState.Idle,model.ascrcpyPreview.state.value)
        }
    }

    @Test fun mediaPollingResumesAfterReplacingAnUnansweredQuery()=withPreview { model, _ ->
        val oldStarted=CompletableDeferred<Unit>()
        val nextStarted=CompletableDeferred<Unit>()
        val servers=listOf(MockWebServer(),MockWebServer())
        for ((index,server) in servers.withIndex()) {
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.path){
                "/status"->MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"fixture","protocol":1,"deviceId":"ui-preview-fixture","tools":[],"modelConfigured":true}"""))
                "/ws"->MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(socket:WebSocket,text:String){
                    val request=Json.parseToJsonElement(text).jsonObject
                    if(index==0){oldStarted.complete(Unit);return}
                    nextStarted.complete(Unit)
                    socket.send("""{"id":${request["id"]},"protocol":1,"ok":true,"result":"PlaybackState {state=3}"}""")
                }})
                else->MockResponse().setResponseCode(404)
            }};server.start()
        }
        try {
            compose.runOnUiThread{model.discovered(DiscoveredService("held media","127.0.0.1",servers[0].port,emptyMap()))}
            compose.waitUntil(10000){oldStarted.isCompleted&&!model.state.value.busy}
            compose.runOnUiThread{model.discovered(DiscoveredService("new media","127.0.0.1",servers[1].port,emptyMap()))}
            compose.waitUntil(10000){nextStarted.isCompleted&&model.state.value.mediaStatus=="正在播放"&&!model.state.value.busy}
            assertTrue(model.state.value.connected)
        }finally{
            compose.runOnUiThread{model.disconnect()}
            compose.waitUntil(10000){!model.state.value.connected}
            servers.forEach{it.shutdown()}
        }
    }

    @Test fun staleSuccessAndErrorCannotOverwriteFeedbackAfterDisconnect() {
        for (fails in listOf(false,true)) withPreview { model, _ ->
            val pending=CompletableDeferred<Unit>()
            val finished=CompletableDeferred<Unit>()
            val action:suspend()->String={
                withContext(NonCancellable){pending.await()}
                finished.complete(Unit)
                if(fails)error("stale failure") else "stale success"
            }
            val execute=RemoteViewModel::class.java.declaredMethods.single{it.name=="execute"}.apply{isAccessible=true}
            compose.runOnUiThread {execute.invoke(model,null,action)}
            compose.runOnUiThread {model.disconnect()}
            compose.waitUntil(10000){!model.state.value.connected}
            compose.runOnUiThread {model.showMessage("current feedback");pending.complete(Unit)}
            compose.waitUntil(10000){finished.isCompleted}
            compose.waitForIdle()
            assertEquals("current feedback",model.state.value.output)
        }
    }

    @Test fun feedbackRestartsFiveSecondTimerAndPlaybackReturnsWithoutMovingKeys() = withPreview { model, _ ->
        compose.onNodeWithTag("remote-playback").assertIsDisplayed()
        val inputBounds = compose.onNodeWithTag("remote-text-controls").fetchSemanticsNode().boundsInRoot
        compose.runOnUiThread { model.showMessage("第一次状态") }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("第一次状态").assertIsDisplayed()
        compose.onNodeWithTag("remote-media-controls").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(4_000)
        compose.runOnUiThread { model.showMessage("第二次状态") }
        compose.mainClock.advanceTimeBy(32)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("第二次状态").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3_100)
        compose.onNodeWithTag("remote-playback").assertIsDisplayed()
        compose.onNodeWithTag("remote-media-controls").performScrollTo().assertIsDisplayed()
        assertEquals(inputBounds, compose.onNodeWithTag("remote-text-controls").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun remoteInputSendsOnlyTextAndPreservesDraftAcrossNavigationAndRotation() = withPreview { model, requests ->
        compose.onNodeWithTag("remote-send-command").assertIsNotEnabled()
        compose.onNodeWithTag("remote-command-input").performTextInput("打开设置")
        compose.onNodeWithTag("device-status").performClick()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("remote-command-input").assertTextContains("打开设置")
        // Recreating the activity keeps the same ViewModel and active fixture connection.
        compose.onNodeWithTag("remote-send-command").performClick()
        compose.waitUntil(10_000) { requests.any { it.type == "text" && it.text == "打开设置" } && !model.state.value.submitting }
        compose.onNodeWithTag("remote-command-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("remote-command-input").performTextInput("返回主页")
        compose.onNodeWithTag("remote-command-input").performImeAction()
        compose.waitUntil(10_000) { requests.any { it.type == "text" && it.text == "返回主页" } && !model.state.value.submitting }
        compose.onNodeWithTag("remote-send-command").assertIsNotEnabled()
        assertEquals(2, requests.count { it.type == "text" })
        assertTrue(requests.filter { it.type == "text" }.all { it.progress })
        assertTrue(requests.none { it.type.startsWith("audio") })
        compose.onNodeWithContentDescription("按住说话").assertDoesNotExist()
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-model").performClick()
        compose.onNodeWithText("语音识别模型").assertDoesNotExist()
    }
    @Test fun expiredFeedbackDoesNotReappearAfterNavigationAndRecreation() = withPreview { model, _ ->
        compose.runOnUiThread { model.text("fixture task") }
        compose.waitUntil(10_000) { model.state.value.feedback.phase == FeedbackPhase.COMPLETE && model.state.value.feedback.text.isNotBlank() }
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithText("已收到操作反馈").assertDoesNotExist()
        Thread.sleep(5200)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithText("已收到操作反馈").assertDoesNotExist()
        compose.onNodeWithTag("remote-feedback").assertDoesNotExist()
        compose.onNodeWithTag("remote-playback").assertExists()
    }

    @Test fun lanOnlyServiceManagementExplainsAdbRequirement() = withPreview { _, _ ->
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-service").performClick()
        compose.onNodeWithText("请先在设备页通过 ADB 连接目标设备，服务管理需要已授权的 ADB 连接。").assertExists()
        compose.onNodeWithText("检查 / 安装在线版本").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("停止目标服务").performScrollTo().assertIsNotEnabled()
    }

    @Test fun servicePageShowsVersionRepositoryActionAndCancellableStop() = withPreview(adbPort = 5555) { _, _ ->
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-service").performClick()
        compose.onNodeWithTag("manage-service-card").assertExists()
        compose.onNodeWithText("服务端版本").assertIsDisplayed()
        compose.onNodeWithText("0.1.0").assertIsDisplayed()
        compose.onNodeWithText("GitHub 在线版本").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("发布清单 HTTPS 地址").assertDoesNotExist()
        compose.onNodeWithText("设备端服务").assertDoesNotExist()
        compose.onNodeWithText("执行结果").assertDoesNotExist()
        compose.onNodeWithText("停止目标服务").performScrollTo().performClick()
        compose.onNodeWithText("停止目标服务？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("停止目标服务？").assertDoesNotExist()
    }

    @Test fun connectedStatusStillOpensDevicesAndCaptureReferenceScreens() = withPreview { _, _ ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun screenshot(name: String) {
            compose.waitForIdle()
            val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
            // Let native press ripples and the window compositor settle before PixelCopy.
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithTag("remote-page").assertIsDisplayed()
        compose.onNodeWithText("已连接").assertIsDisplayed()
        screenshot("ui-preview-remote")
        compose.onNodeWithTag("device-status").performClick()
        compose.onNodeWithTag("devices-page").assertIsDisplayed()
        screenshot("ui-preview-devices")
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("open-menu").performClick()
        screenshot("ui-preview-menu")
        compose.onNodeWithTag("feature-permissions").performClick()
        compose.onNodeWithText("ADB shell").assertIsDisplayed()
    }
}
