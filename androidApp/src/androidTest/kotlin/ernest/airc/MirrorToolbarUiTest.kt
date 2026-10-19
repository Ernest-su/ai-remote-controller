package ernest.airc

import android.content.res.Configuration
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Android view/IME/layout fixtures; offline remote actions never leave the controller. */
class MirrorToolbarUiTest {
    val compose=createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)
    private fun model():RemoteViewModel {
        lateinit var result:RemoteViewModel
        compose.runOnUiThread {result=ViewModelProvider(compose.activity)[RemoteViewModel::class.java]}
        return result
    }
    private fun surface():SurfaceView? {
        fun find(view:View):SurfaceView? {
            if(view is SurfaceView)return view
            if(view is ViewGroup)for(index in 0 until view.childCount)find(view.getChildAt(index))?.let {return it}
            return null
        }
        var result:SurfaceView?=null
        compose.runOnUiThread {result=find(compose.activity.window.decorView)}
        return result
    }
    private fun screenshot(name:String) {
        val file=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,name+".png")
        file.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun toolbarIconsFollowTitleAndDoNotExposeManualStartOrSize() {
        val model=model()
        compose.setContent {RemoteTheme {ScreenshotScreen(RemoteUiState(),model)}}
        val title=compose.onNodeWithText("动态预览").fetchSemanticsNode().boundsInRoot
        val remote=compose.onNodeWithTag("mirror-toggle-remote").fetchSemanticsNode().boundsInRoot
        val full=compose.onNodeWithTag("mirror-fullscreen").fetchSemanticsNode().boundsInRoot
        assertTrue(title.right<=remote.left);assertTrue(remote.right<=full.left)
        assertEquals(remote.center.y,full.center.y,1f)
        compose.onNodeWithTag("mirror-fullscreen").assertContentDescriptionEquals("全屏预览").assertIsNotEnabled()
        compose.onNodeWithText("开始屏幕镜像").assertDoesNotExist()
        compose.onNodeWithText("停止屏幕镜像").assertDoesNotExist()
        compose.onNodeWithText("预览尺寸").assertDoesNotExist()
        compose.onNodeWithText("1920 · 8 Mbps · 60 fps").assertDoesNotExist()
        val previous = model.ascrcpyPreview.quality.value
        try {
            compose.onNodeWithText("流畅").performScrollTo().performClick()
            assertEquals(PreviewQuality.FLUENT, model.ascrcpyPreview.quality.value)
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
            try {
                val restored = AscrcpyPreviewController(compose.activity, scope) { error("Offline fixture must not connect") }
                assertEquals(PreviewQuality.FLUENT, restored.quality.value)
            } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
            compose.onNodeWithText("均衡").performClick()
            assertEquals(PreviewQuality.BALANCED, model.ascrcpyPreview.quality.value)
        } finally { compose.runOnUiThread { model.ascrcpyPreview.selectQuality(previous) } }
    }
    @Test fun fullscreenKeepsNativeViewAndFloatingRemoteDraftAndScalesBothOrientations() {
        val model=model()
        var full by mutableStateOf(false)
        var dark by mutableStateOf(false)
        val original=Configuration(compose.activity.resources.configuration)
        var config by mutableStateOf(Configuration(original).apply {screenWidthDp=360;screenHeightDp=800})
        compose.setContent {CompositionLocalProvider(LocalConfiguration provides config) {RemoteTheme(dark) {
            Box(Modifier.fillMaxSize()) {ScreenshotScreen(RemoteUiState(modelConfigured = true),model,full,{full=it})}
        }}}
        compose.waitUntil(5000) {surface()?.holder?.surface?.isValid==true}
        val before=checkNotNull(surface())
        compose.onNodeWithTag("mirror-toggle-remote").performClick()
        compose.onNodeWithTag("mirror-floating-remote").assertWidthIsEqualTo(androidx.compose.ui.unit.Dp(240f))
        compose.onNodeWithTag("compact-mirror-remote").assertExists()
        compose.onNodeWithContentDescription("向上").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        compose.onNodeWithContentDescription("切换为完整遥控").performClick()
        compose.onNodeWithTag("mirror-floating-remote").assertWidthIsEqualTo(RemoteDimensions.MirrorRemoteMinimumWidth)
        compose.onNodeWithTag("remote-command-input").performTextInput("悬浮草稿")
        compose.onNodeWithContentDescription("切换为紧凑遥控").performClick()
        compose.onNodeWithTag("compact-mirror-remote").assertExists()
        compose.onNodeWithContentDescription("切换为完整遥控").performClick()
        compose.onNodeWithTag("remote-command-input").assertTextContains("悬浮草稿")
        compose.runOnIdle {full=true}
        compose.onNodeWithTag("mirror-floating-remote").assertExists()
        compose.onNodeWithTag("remote-command-input").assertTextContains("悬浮草稿")
        assertSame(before,surface())
        screenshot("mirror-floating-light")
        compose.runOnIdle {dark=true}
        screenshot("mirror-floating-dark")
        compose.onNodeWithTag("mirror-exit-fullscreen").assertContentDescriptionEquals("退出全屏")
        compose.onNodeWithText("退出全屏").assertDoesNotExist()
        compose.onNodeWithTag("mirror-close-remote").performClick()
        compose.onNodeWithTag("mirror-floating-remote").assertDoesNotExist()
        compose.onNodeWithTag("mirror-toggle-remote").performClick()
        compose.onNodeWithTag("remote-command-input").assertTextContains("悬浮草稿")
        compose.runOnIdle {config=Configuration(config).apply {screenWidthDp=800;screenHeightDp=360}}
        compose.onNodeWithTag("mirror-floating-remote").assertWidthIsEqualTo(androidx.compose.ui.unit.Dp(180f))
        compose.onNodeWithTag("mirror-exit-fullscreen").performClick()
        assertSame(before,surface())
        compose.onNodeWithTag("remote-command-input").assertTextContains("悬浮草稿")
    }
}
