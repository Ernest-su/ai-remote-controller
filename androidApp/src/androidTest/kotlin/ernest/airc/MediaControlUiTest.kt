package ernest.airc

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MediaControlUiTest {
    val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)
    private val player = MediaSession("com.test.music", true, 2, 17000, 247000, seekSupported = true,
        description = "Hope (希望)", sessionTag = "fixture", appName = "音乐播放器", artist = "示例歌手", actions = 310)

    @Test fun quietUpdatesKeepControlsStableAndPrecisionDraftSurvivesRestore() {
        var state by mutableStateOf(MediaControlState(listOf(player), player.selection(), seekAvailable = true,
            loaded = true, transportAvailable = true))
        var available by mutableStateOf(true)
        var dark by mutableStateOf(false)
        val commands = mutableListOf<String>()
        val positions = mutableListOf<Long>()
        val volumeKeys = mutableListOf<String>()
        var volume by mutableStateOf(RemoteVolume(57,0,100))
        var volumeSetting by mutableStateOf(false)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RemoteTheme(darkTheme = dark) { Box(Modifier.width(320.dp).height(680.dp).testTag("media-review")) {
                    MediaControlPanel(state, available, {}, { state = state.copy(selected = it) }, { positions += it }, onCommand = { commands += it },
                        volumeControls = { PlaybackVolumeControls(volume, available, volumeSetting, { volume = volume.copy(index = (it * 100).toInt()) }, { volumeKeys += it }) })
                } }
            }
        }
        compose.onNodeWithText("Hope (希望)").assertIsDisplayed()
        compose.onNodeWithText("com.test.music").assertDoesNotExist()
        val play = compose.onNodeWithTag("media-command-play")
        play.performScrollTo().assertIsEnabled().assertHeightIsAtLeast(72.dp)
        val bounds = play.fetchSemanticsNode().boundsInRoot
        val volumeBounds = compose.onNodeWithTag("media-volume-controls").fetchSemanticsNode().boundsInRoot
        assertTrue(volumeBounds.bottom <= bounds.top)
        compose.onNodeWithTag("remote-volume-value").assertTextEquals("57%")
        for (name in listOf("静音", "音量 −", "音量 +")) compose.onNodeWithContentDescription(name).performScrollTo().performClick()
        assertEquals(listOf("mute", "volume_down", "volume_up"), volumeKeys)
        val volumeSlider = compose.onNodeWithTag("remote-volume-slider")
        volumeSlider.performScrollTo()
        val vb = volumeSlider.fetchSemanticsNode().boundsInRoot
        val vt = compose.onNodeWithTag("remote-volume-thumb", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        volumeSlider.performTouchInput { swipe(vt - vb.topLeft, Offset(width * .8f, centerY), 400) }
        assertTrue(volume.index > 57)
        play.performScrollTo()
        compose.runOnIdle { state = state.copy(refreshing = true) }
        play.assertIsEnabled()
        assertEquals(bounds, play.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("media-refresh").assertIsEnabled()
        play.performClick()
        assertEquals(listOf("play"), commands)
        compose.onNodeWithText("已暂停").assertExists() // Submission alone cannot claim playback.
        compose.runOnIdle { state = state.copy(sessions = listOf(player.copy(actions = 512, state = 3))) }
        compose.onNodeWithTag("media-command-pause").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("play", "pause"), commands)
        compose.runOnIdle { state = state.copy(sessions = listOf(player.copy(actions = 512, state = 2))) }
        play.assertIsEnabled().performClick()
        assertEquals(listOf("play", "pause", "play"), commands)
        compose.runOnIdle { state = state.copy(sessions = listOf(player), refreshing = false) }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            compose.onNodeWithText("播放来源").performScrollTo()
            val image = compose.onNodeWithTag("media-review").captureToImage().asAndroidBitmap()
            val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            File(directory, "media-control-${if (mode) "dark" else "light"}.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
        compose.onNodeWithTag("media-precise-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("media-seek-position").performScrollTo().performTextInput("0:24")
        compose.runOnIdle { state = state.copy(sessions = listOf(player.copy(positionMs = 18000)), refreshing = true) }
        compose.onNodeWithTag("media-seek-position").assertTextContains("0:24")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("media-seek-position").performScrollTo().assertTextContains("0:24")
        compose.onNodeWithText("跳转", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(listOf(24000L), positions)
        val slider = compose.onNodeWithTag("media-seek-slider")
        slider.performScrollTo()
        val sliderBounds = slider.fetchSemanticsNode().boundsInRoot
        val thumb = compose.onNodeWithTag("media-seek-thumb", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        slider.performTouchInput { swipe(thumb - sliderBounds.topLeft, Offset(width * .6f, centerY), 300) }
        assertEquals(2, positions.size)
        assertTrue(positions.last() in 24001..247000)
        compose.runOnIdle { available = false }
        compose.onNodeWithTag("media-command-play").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("media-seek-slider").assertIsNotEnabled()
        compose.onNodeWithTag("remote-volume-slider").assertIsNotEnabled()
        compose.onNodeWithContentDescription("静音").assertIsNotEnabled()
        compose.runOnIdle { available = true; state = state.copy(busy = true, refreshing = false) }
        play.assertIsNotEnabled()
        compose.runOnIdle { volumeSetting = true }
        compose.onNodeWithTag("remote-volume-slider").assertIsNotEnabled()
        compose.onNodeWithContentDescription("音量 +").assertIsNotEnabled()
    }

    @Test fun pollingIsOwnedByTheVisiblePlayerAndPausesInBackground() {
        val queries = AtomicInteger()
        val commands = AtomicInteger()
        val secondStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/status" -> MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"fixture","protocol":1,"deviceId":"media-ui-fixture","tools":["media.status","media.seek","media.command"]}"""))
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(socket: WebSocket, text: String) {
                        val request = Json.decodeFromString<RuntimeRequest>(text)
                        val result = when (request.tool) {
                            "media.status" -> {
                                if (queries.incrementAndGet() == 2) { secondStarted.countDown(); check(release.await(8, TimeUnit.SECONDS)) }
                                Json.encodeToString(MediaSessions(listOf(player), seekAvailable = true, transportAvailable = true))
                            }
                            "media.command" -> {
                                assertEquals("com.test.music", request.arguments["package"]!!.jsonPrimitive.content)
                                assertEquals("fixture", request.arguments["sessionTag"]!!.jsonPrimitive.content)
                                assertEquals("play", request.arguments["command"]!!.jsonPrimitive.content)
                                commands.incrementAndGet()
                                """{"packageName":"com.test.music","sessionTag":"fixture","command":"play","submitted":true}"""
                            }
                            "device.info" -> """{"sdk":35}"""
                            else -> ""
                        }
                        socket.send(Json.encodeToString(RuntimeResponse(request.id, true, result = result)))
                    }
                })
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = ViewModelStore()
        lateinit var model: RemoteViewModel
        try {
            compose.runOnUiThread { model = RemoteViewModel(context.applicationContext as Application); owner.put("media", model) }
            compose.setContent { RemoteTheme { val state by model.state.collectAsState(); MediaControls(state, model) } }
            compose.runOnUiThread { model.discovered(DiscoveredService("Player fixture", "127.0.0.1", server.port, emptyMap())) }
            compose.waitUntil(10000) { model.mediaControl.state.value.loaded }
            assertEquals(1, queries.get())
            compose.waitUntil(6000) { secondStarted.count == 0L }
            assertTrue(model.mediaControl.state.value.refreshing)
            assertFalse(model.mediaControl.state.value.busy)
            compose.onNodeWithTag("media-command-play").assertIsEnabled()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            release.countDown()
            val stopped = queries.get()
            Thread.sleep(3500)
            assertEquals(stopped, queries.get())
            assertFalse(model.mediaControl.state.value.refreshing)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(5000) { queries.get() > stopped }
            compose.onNodeWithTag("media-title").assertTextEquals("Hope (希望)")
            compose.onNodeWithTag("media-command-play").performClick()
            compose.waitUntil(5000) { commands.get() == 1 && !model.mediaControl.state.value.busy }
            assertNull(model.mediaControl.state.value.error)
            compose.onNodeWithText("已暂停").assertExists()
        } finally {
            release.countDown()
            compose.runOnUiThread { owner.clear() }
            server.shutdown()
        }
    }
}
