package ernest.airc

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FloatingRemoteBehaviorUiTest {
    val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)
    private fun model(): RemoteViewModel {
        lateinit var model: RemoteViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java] }
        return model
    }

    @Test fun transparencySettingIsOfflineAccessibleAndSurvivesReload() {
        val model = model()
        val app = compose.activity.application
        val prefs = app.getSharedPreferences("remote-controls", 0)
        val existed = prefs.contains("floating-transparency")
        val original = prefs.getInt("floating-transparency", 0)
        val store = ViewModelStore()
        try {
            compose.setContent {
                val state by model.state.collectAsState()
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    RemoteTheme { Column(Modifier.width(320.dp).height(640.dp).verticalScroll(rememberScrollState())) {
                        FloatingRemoteSettings(state.floatingRemoteTransparency, model::setFloatingRemoteTransparency)
                    } }
                }
            }
            compose.onNodeWithTag("floating-remote-transparency").performScrollTo().assertIsEnabled()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(75f) }
            compose.onNodeWithText("75%").assertIsDisplayed()
            capture("floating-remote-settings")
            compose.runOnUiThread {
                assertEquals(75, model.state.value.floatingRemoteTransparency)
                val reloaded = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[RemoteViewModel::class.java]
                assertEquals(75, reloaded.state.value.floatingRemoteTransparency)
                store.clear()
                model.setFloatingRemoteTransparency(0)
                assertEquals(60, model.state.value.floatingRemoteTransparency)
                model.setFloatingRemoteTransparency(100)
                assertEquals(90, model.state.value.floatingRemoteTransparency)
            }
            compose.onNodeWithTag("floating-remote-transparency").performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
            compose.onNodeWithText("60%").assertIsDisplayed()
            compose.onNodeWithTag("floating-remote-transparency").performSemanticsAction(SemanticsActions.SetProgress) { it(90f) }
            compose.onNodeWithText("90%").assertIsDisplayed()
        } finally {
            compose.runOnUiThread {
                store.clear()
                model.setFloatingRemoteTransparency(original)
                if (existed) prefs.edit().putInt("floating-transparency", original).commit()
                else prefs.edit().remove("floating-transparency").commit()
            }
        }
    }

    @Test fun idleFadesAndTouchRestoresWhileHeldTouchNeverFades() {
        val model = model()
        var transparency by mutableIntStateOf(RemoteDimensions.FloatingRemoteDefaultTransparency)
        compose.setContent { RemoteTheme {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                MirrorFloatingRemote(RemoteUiState(floatingRemoteTransparency = transparency), model, {}, {})
            }
        } }
        val panel = compose.onNodeWithTag("mirror-floating-remote")
        fun state(value: String) = panel.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
        fun brightness(): Float {
            val pixels = panel.captureToImage().toPixelMap()
            return pixels[10, pixels.height / 3].red
        }
        compose.mainClock.autoAdvance = false
        state("操作中")
        val activeBrightness = brightness()
        compose.mainClock.advanceTimeBy(RemoteDimensions.FloatingRemoteIdleDelayMillis + 500)
        state("闲置淡化")
        assertTrue(brightness() < activeBrightness * 0.7f)
        capture("floating-remote-idle")
        panel.performTouchInput { down(Offset(10f, 120f)) }
        compose.mainClock.advanceTimeByFrame()
        state("操作中")
        compose.mainClock.advanceTimeBy(6000)
        state("操作中")
        panel.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { transparency = 90 }
        compose.mainClock.advanceTimeBy(100)
        val adjustedBrightness = brightness()
        capture("floating-remote-90-transparency")
        assertTrue("90% transparency must affect rendered pixels: $activeBrightness -> $adjustedBrightness",
            adjustedBrightness < activeBrightness * 0.7f)
        compose.mainClock.advanceTimeBy(6000)
        state("闲置淡化")
    }

    private fun capture(name: String) {
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun releaseNearAnEdgeSnapsWithoutStoppingContinuousDragging() {
        val model = model()
        compose.setContent { RemoteTheme { Box(Modifier.fillMaxSize()) {
            MirrorFloatingRemote(RemoteUiState(), model, {}, {})
        } } }
        val panel = compose.onNodeWithTag("mirror-floating-remote")
        val handle = compose.onNodeWithTag("mirror-remote-drag")
        handle.performTouchInput { swipe(center, center + Offset(120f, 100f), 300) }
        assertEquals(120f, panel.fetchSemanticsNode().boundsInRoot.left, 1f)
        handle.performTouchInput { swipe(center, center - Offset(100f, 0f), 300) }
        assertEquals(0f, panel.fetchSemanticsNode().boundsInRoot.left, 1f)
        assertEquals(100f, panel.fetchSemanticsNode().boundsInRoot.top, 1f)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val before = panel.fetchSemanticsNode().boundsInRoot
        handle.performTouchInput { swipe(center, center + Offset(root.right - before.right - 10f, 0f), 500) }
        assertEquals(root.right, panel.fetchSemanticsNode().boundsInRoot.right, 1f)
    }
}
