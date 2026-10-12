package ernest.airc

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import ernest.ascrcpy.scrcpy.ScrcpyState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
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
    @Test fun backgroundStopsCaptureAndForegroundRequiresManualRestart()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val host=args.getString("mirrorTargetHost");val port=args.getString("mirrorTargetPort")?.toIntOrNull()
        assumeTrue("Explicit independent mirror target required",host!=null&&port!=null)
        lateinit var model:RemoteViewModel;var initialized=false;var deviceId:String?=null
        val observer=RuntimeConnection()
        suspend fun await(stage:String,condition:()->Boolean){try{withTimeout(15000){while(!condition())delay(50)}}catch(error:TimeoutCancellationException){throw AssertionError("Mirror timeout at $stage",error)}}
        try {
            deviceId=observer.connect(host!!,port!!).deviceId
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
            compose.onNodeWithText("开始屏幕镜像").performScrollTo().performClick()
            await("decoded frame"){(model.ascrcpyPreview.state.value as? ScrcpyState.Streaming)?.renderedFrames?.let{it>0}==true||model.ascrcpyPreview.state.value is ScrcpyState.Failed}
            assertTrue("Actual preview state: ${model.ascrcpyPreview.state.value}",model.ascrcpyPreview.state.value is ScrcpyState.Streaming)
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
            assertEquals(ScrcpyState.Idle,model.ascrcpyPreview.state.value)
            assertRetired(beforeBackground)
            compose.onNodeWithText("开始屏幕镜像").performScrollTo().assertIsEnabled().performClick()
            await("decoded frame"){(model.ascrcpyPreview.state.value as? ScrcpyState.Streaming)?.renderedFrames?.let{it>0}==true||model.ascrcpyPreview.state.value is ScrcpyState.Failed}
            assertTrue("Actual preview state: ${model.ascrcpyPreview.state.value}",model.ascrcpyPreview.state.value is ScrcpyState.Streaming)
            val beforeStop=previewView()
            compose.onNodeWithText("停止屏幕镜像").performScrollTo().performClick()
            await("capture stopped"){model.ascrcpyPreview.state.value==ScrcpyState.Idle}
            assertRetired(beforeStop)
        }finally{
            observer.close()
            if(initialized)compose.runOnUiThread{model.disconnect()}
            deviceId?.let{DeviceStore(instrumentation.targetContext).remove(it)}
        }
    }
    @Test fun physicalOrientationChangeClearsCaptureAndPreservesSettings()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val host=args.getString("mirrorTargetHost");val port=args.getString("mirrorTargetPort")?.toIntOrNull()
        assumeTrue("Explicit independent mirror target required",host!=null&&port!=null)
        lateinit var model:RemoteViewModel;var initialized=false;var deviceId:String?=null
        val observer=RuntimeConnection();val originalActivity=compose.activity
        val originalOrientation=originalActivity.requestedOrientation
        suspend fun await(stage:String,condition:()->Boolean){try{withTimeout(15000){while(!condition())delay(50)}}catch(error:TimeoutCancellationException){throw AssertionError("Mirror timeout at $stage",error)}}
        try {
            deviceId=observer.connect(host!!,port!!).deviceId
            val fixture=RememberedDevice(deviceId!!,"Owned ascrcpy fixture",ConnectionMethod.TCP,host,args.getString("ascrcpyTargetPort")?.toIntOrNull() ?: 5555,null,port,System.currentTimeMillis())
            DeviceStore(instrumentation.targetContext).remember(fixture)
            compose.runOnUiThread{
                model=ViewModelProvider(compose.activity)[RemoteViewModel::class.java];initialized=true
                model.reconnect(fixture)
            }
            await("connection"){model.state.value.connected&&!model.state.value.busy}
            compose.onNodeWithTag("open-menu").performClick()
            compose.onNodeWithTag("feature-screenshot").performScrollTo().performClick()
            compose.onNodeWithText("1920 · 8 Mbps · 60 fps").performScrollTo().assertExists()
            compose.onNodeWithText("开始屏幕镜像").performScrollTo().performClick()
            await("decoded frame"){(model.ascrcpyPreview.state.value as? ScrcpyState.Streaming)?.renderedFrames?.let{it>0}==true||model.ascrcpyPreview.state.value is ScrcpyState.Failed}
            assertTrue("Actual preview state: ${model.ascrcpyPreview.state.value}",model.ascrcpyPreview.state.value is ScrcpyState.Streaming)
            val before=compose.activity
            val orientation=before.resources.configuration.orientation
            compose.runOnUiThread{before.requestedOrientation=if(orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT)
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            await("orientation recreation"){compose.activity!==before&&compose.activity.resources.configuration.orientation!=orientation}
            await("capture stopped"){model.ascrcpyPreview.state.value==ScrcpyState.Idle}
            compose.waitForIdle()
            assertEquals(ScrcpyState.Idle,model.ascrcpyPreview.state.value)
            compose.onNodeWithText("1920 · 8 Mbps · 60 fps").performScrollTo().assertExists()
            assertTrue(withTimeout(10000){observer.screenshot()}.isNotEmpty())
            compose.onNodeWithText("开始屏幕镜像").performScrollTo().assertIsEnabled().performClick()
            await("decoded frame"){(model.ascrcpyPreview.state.value as? ScrcpyState.Streaming)?.renderedFrames?.let{it>0}==true||model.ascrcpyPreview.state.value is ScrcpyState.Failed}
            assertTrue("Actual preview state: ${model.ascrcpyPreview.state.value}",model.ascrcpyPreview.state.value is ScrcpyState.Streaming)
            val beforeStop=previewView()
            compose.onNodeWithText("停止屏幕镜像").performScrollTo().performClick()
            await("capture stopped"){model.ascrcpyPreview.state.value==ScrcpyState.Idle}
            assertRetired(beforeStop)
        }finally{
            observer.close()
            if(initialized)compose.runOnUiThread{model.disconnect()}
            compose.runOnUiThread{compose.activity.requestedOrientation=originalOrientation}
            deviceId?.let{DeviceStore(instrumentation.targetContext).remove(it)}
        }
    }

}
