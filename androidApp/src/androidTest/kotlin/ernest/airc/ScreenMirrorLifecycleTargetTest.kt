package ernest.airc

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import ernest.ascrcpy.scrcpy.ScrcpyState
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Actual phone task background/foreground with a separately owned Android encoder. */
class ScreenMirrorLifecycleTargetTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun previewView():SurfaceView {
        fun find(view:View):SurfaceView? {
            if(view is SurfaceView)return view
            if(view is ViewGroup)for(index in 0 until view.childCount)find(view.getChildAt(index))?.let{return it}
            return null
        }
        var found:SurfaceView?=null
        compose.runOnUiThread{found=find(compose.activity.window.decorView)}
        return checkNotNull(found){"Preview SurfaceView missing"}
    }
    private fun assertRetired(view:SurfaceView) {
        compose.waitUntil(15000){!view.isAttachedToWindow}
        compose.waitForIdle()
        assertFalse("Stopped preview must release its old Surface",view.holder.surface.isValid)
        assertNotSame("Stopped preview must replace the old buffer-bearing view",view,previewView())
    }
    private fun screenshot(name:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {java.io.File(instrumentation.targetContext.filesDir,name+".png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
        finally {bitmap.recycle()}
    }
    @Test fun backgroundStopsCaptureAndForegroundAutomaticallyRestarts()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val host=args.getString("mirrorTargetHost");val port=args.getString("mirrorTargetPort")?.toIntOrNull()
        assumeTrue("Explicit independent mirror target required",host!=null&&port!=null)
        lateinit var model:RemoteViewModel;var initialized=false;var deviceId:String?=null
        val observer=RuntimeConnection()
        fun await(stage:String,condition:()->Boolean){try{compose.waitUntil(15000,condition)}catch(error:ComposeTimeoutException){throw AssertionError("Mirror timeout at $stage; preview=${if(initialized) model.ascrcpyPreview.state.value else null}",error)}}
        try {
            deviceId=observer.connect(host!!,port!!).deviceId
            // This suite owns the temporary target runtime; no provider request is sent.
            observer.request("configure", arguments = buildJsonObject {
                put("baseUrl", "https://fixture.invalid/v1")
                put("apiKey", "fixture-key")
                put("model", "fixture-model")
            })
            val fixture=RememberedDevice(deviceId!!,"Owned ascrcpy fixture",ConnectionMethod.TCP,host,args.getString("ascrcpyTargetPort")?.toIntOrNull() ?: 5555,null,port,System.currentTimeMillis())
            DeviceStore(instrumentation.targetContext).remember(fixture)
            compose.runOnUiThread{
                model=ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
                initialized=true
                model.reconnect(fixture)
            }
            await("connection"){model.state.value.connected&&!model.state.value.busy}
            compose.onNodeWithTag("open-menu").performClick()
            compose.onNodeWithTag("feature-screenshot").performScrollTo().performClick()
            await("decoded frame"){(model.ascrcpyPreview.state.value as? ScrcpyState.Streaming)?.renderedFrames?.let{it>0}==true||model.ascrcpyPreview.state.value is ScrcpyState.Failed}
            assertTrue("Actual preview state: ${model.ascrcpyPreview.state.value}",model.ascrcpyPreview.state.value is ScrcpyState.Streaming)
            repeat(2) {
                val beforeFull=previewView()
                val frames=(model.ascrcpyPreview.state.value as ScrcpyState.Streaming).renderedFrames
                compose.onNodeWithTag("mirror-fullscreen").performScrollTo().performClick()
                await("fullscreen advancing frames"){(model.ascrcpyPreview.state.value as? ScrcpyState.Streaming)?.renderedFrames?.let{it>frames+3}==true}
                assertSame("Fullscreen must keep the native video view",beforeFull,previewView())
                compose.onNodeWithTag("mirror-toggle-remote").performClick()
                compose.onNodeWithTag("mirror-floating-remote").assertExists()
                if(it==0)screenshot("mirror-live-portrait")
                compose.onNodeWithTag("mirror-close-remote").performClick()
                compose.onNodeWithTag("mirror-exit-fullscreen").performClick()
                await("inline advancing frames"){(model.ascrcpyPreview.state.value as? ScrcpyState.Streaming)?.renderedFrames?.let{it>frames+6}==true}
                assertSame("Returning inline must keep the native video view",beforeFull,previewView())
            }
            val beforeBackground=previewView()
            compose.runOnUiThread{assertTrue(compose.activity.moveTaskToBack(true))}
            await("background lifecycle"){!compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)}
            await("capture stopped"){model.ascrcpyPreview.state.value==ScrcpyState.Idle}
            assertEquals(ScrcpyState.Idle,model.ascrcpyPreview.state.value)
            assertTrue(withTimeout(10000){observer.screenshot()}.isNotEmpty())
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            await("foreground lifecycle"){compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
            compose.waitForIdle()
            assertRetired(beforeBackground)
            await("decoded frame"){(model.ascrcpyPreview.state.value as? ScrcpyState.Streaming)?.renderedFrames?.let{it>0}==true||model.ascrcpyPreview.state.value is ScrcpyState.Failed}
            assertTrue("Actual preview state: ${model.ascrcpyPreview.state.value}",model.ascrcpyPreview.state.value is ScrcpyState.Streaming)
            val beforeLeaving=previewView()
            compose.onNodeWithTag("navigate-back").performClick()
            await("capture stopped"){model.ascrcpyPreview.state.value==ScrcpyState.Idle}
            compose.waitUntil(10000){!beforeLeaving.isAttachedToWindow}
        }finally{
            observer.close()
            if(initialized)compose.runOnUiThread{model.disconnect()}
            deviceId?.let{DeviceStore(instrumentation.targetContext).remove(it)}
        }
    }
    @Test fun physicalOrientationChangeRestoresCaptureAndPreservesSettings()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val host=args.getString("mirrorTargetHost");val port=args.getString("mirrorTargetPort")?.toIntOrNull()
        assumeTrue("Explicit independent mirror target required",host!=null&&port!=null)
        lateinit var model:RemoteViewModel;var initialized=false;var deviceId:String?=null
        val observer=RuntimeConnection();val originalActivity=compose.activity
        val originalOrientation=originalActivity.requestedOrientation
        fun await(stage:String,condition:()->Boolean){try{compose.waitUntil(15000,condition)}catch(error:ComposeTimeoutException){throw AssertionError("Mirror timeout at $stage; preview=${if(initialized) model.ascrcpyPreview.state.value else null}",error)}}
        try {
            deviceId=observer.connect(host!!,port!!).deviceId
            // This suite owns the temporary target runtime; no provider request is sent.
            observer.request("configure", arguments = buildJsonObject {
                put("baseUrl", "https://fixture.invalid/v1")
                put("apiKey", "fixture-key")
                put("model", "fixture-model")
            })
            val fixture=RememberedDevice(deviceId!!,"Owned ascrcpy fixture",ConnectionMethod.TCP,host,args.getString("ascrcpyTargetPort")?.toIntOrNull() ?: 5555,null,port,System.currentTimeMillis())
            DeviceStore(instrumentation.targetContext).remember(fixture)
            compose.runOnUiThread{
                model=ViewModelProvider(compose.activity)[RemoteViewModel::class.java];initialized=true
                model.reconnect(fixture)
            }
            await("connection"){model.state.value.connected&&!model.state.value.busy}
            compose.onNodeWithTag("open-menu").performClick()
            compose.onNodeWithTag("feature-screenshot").performScrollTo().performClick()
            compose.onNodeWithText("1920 · 8 Mbps · 60 fps").assertDoesNotExist()
            await("decoded frame"){(model.ascrcpyPreview.state.value as? ScrcpyState.Streaming)?.renderedFrames?.let{it>0}==true||model.ascrcpyPreview.state.value is ScrcpyState.Failed}
            assertTrue("Actual preview state: ${model.ascrcpyPreview.state.value}",model.ascrcpyPreview.state.value is ScrcpyState.Streaming)
            compose.onNodeWithTag("mirror-toggle-remote").performClick()
            compose.onNodeWithTag("remote-command-input").performTextInput("旋转保留草稿")
            compose.runOnUiThread {
                val input=compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                input.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken,0)
            }
            compose.onNodeWithTag("mirror-fullscreen").performScrollTo().performClick()
            val before=compose.activity
            val orientation=before.resources.configuration.orientation
            compose.runOnUiThread{before.requestedOrientation=if(orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT)
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            await("orientation recreation"){compose.activity!==before&&compose.activity.resources.configuration.orientation!=orientation}
            compose.waitForIdle()
            compose.onNodeWithText("1920 · 8 Mbps · 60 fps").assertDoesNotExist()
            assertTrue(withTimeout(10000){observer.screenshot()}.isNotEmpty())
            await("decoded frame"){(model.ascrcpyPreview.state.value as? ScrcpyState.Streaming)?.renderedFrames?.let{it>0}==true||model.ascrcpyPreview.state.value is ScrcpyState.Failed}
            assertTrue("Actual preview state: ${model.ascrcpyPreview.state.value}",model.ascrcpyPreview.state.value is ScrcpyState.Streaming)
            compose.onNodeWithTag("mirror-exit-fullscreen").assertExists()
            compose.onNodeWithTag("remote-command-input").assertTextContains("旋转保留草稿")
            val config=compose.activity.resources.configuration
            val expected=mirrorFloatingRemoteWidth(config.screenWidthDp.dp,config.screenHeightDp.dp)
            compose.onNodeWithTag("mirror-floating-remote").assertWidthIsEqualTo(expected)
            screenshot("mirror-live-landscape")
            compose.onNodeWithTag("mirror-close-remote").performClick()
            compose.onNodeWithTag("mirror-exit-fullscreen").performClick()
            val beforeLeaving=previewView()
            compose.onNodeWithTag("navigate-back").performClick()
            await("capture stopped"){model.ascrcpyPreview.state.value==ScrcpyState.Idle}
            compose.waitUntil(10000){!beforeLeaving.isAttachedToWindow}
        }finally{
            observer.close()
            if(initialized)compose.runOnUiThread{model.disconnect()}
            compose.runOnUiThread{compose.activity.requestedOrientation=originalOrientation}
            deviceId?.let{DeviceStore(instrumentation.targetContext).remove(it)}
        }
    }

}
