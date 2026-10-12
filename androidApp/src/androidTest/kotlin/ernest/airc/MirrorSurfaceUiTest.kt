package ernest.airc

import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import ernest.ascrcpy.scrcpy.ScrcpyState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Controlled connection failure with real Android Surface lifecycle; no remote actions. */
class MirrorSurfaceUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun stalledFirstFrameExpiresAtRealDeadlineAndReleasesSession()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val adb=MalformedPreviewAdb(stallAfterCodec=true)
        val controller=AscrcpyPreviewController(InstrumentationRegistry.getInstrumentation().targetContext,scope){
            AdbSession(adb,"fixture.invalid",1,null)
        }
        val current=AtomicReference<Surface?>()
        val keepScreenOn=android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val originalKeepScreenOn=compose.activity.window.attributes.flags and keepScreenOn!=0
        try {
            compose.runOnUiThread{compose.activity.window.addFlags(keepScreenOn)}
            compose.setContent {
                val state by controller.state.collectAsState()
                val epoch by controller.surfaceEpoch.collectAsState()
                MirrorSurface(epoch,state !is ScrcpyState.Idle && state !is ScrcpyState.Failed,16f/9f,
                    onSurface={current.set(it)},onDestroyed=controller::stop)
            }
            compose.waitUntil(10000){current.get()?.isValid==true}
            val before=checkNotNull(current.get())
            val started=android.os.SystemClock.elapsedRealtime()
            compose.runOnUiThread{controller.start(before)}
            compose.waitUntil(5000){controller.state.value==ScrcpyState.WaitingForVideo}
            compose.waitUntil(60000){controller.state.value is ScrcpyState.Failed && adb.closed &&
                adb.removals.get()==1 && adb.channels.all{it.closed} && !before.isValid && current.get()?.isValid==true}
            val elapsed=android.os.SystemClock.elapsedRealtime()-started
            assertTrue("Use the production 45-second deadline: elapsed=$elapsed",elapsed in 44_500..60_000)
            assertEquals("动态预览启动超时",(controller.state.value as ScrcpyState.Failed).cause.message)
            assertTrue(adb.channels.size>=2)
        }finally{
            controller.stop();scope.coroutineContext[Job]?.cancelAndJoin()
            if(!originalKeepScreenOn)compose.runOnUiThread{compose.activity.window.clearFlags(keepScreenOn)}
        }
    }
    @Test fun invalidVideoCodecClosesChannelsSessionAndRemovesUploadedServer()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val adb=MalformedPreviewAdb()
        val controller=AscrcpyPreviewController(InstrumentationRegistry.getInstrumentation().targetContext,scope){
            AdbSession(adb,"fixture.invalid",1,null)
        }
        val current=AtomicReference<Surface?>()
        try {
            compose.setContent {
                val state by controller.state.collectAsState()
                val epoch by controller.surfaceEpoch.collectAsState()
                MirrorSurface(epoch,state !is ScrcpyState.Idle && state !is ScrcpyState.Failed,16f/9f,
                    onSurface={current.set(it)},onDestroyed=controller::stop)
            }
            compose.waitUntil(10000){current.get()?.isValid==true}
            val before=checkNotNull(current.get())
            compose.runOnUiThread{controller.start(before)}
            compose.waitUntil(10000){controller.state.value is ScrcpyState.Failed && adb.closed &&
                adb.removals.get()==1 && !before.isValid && current.get()?.isValid==true}
            assertTrue((controller.state.value as ScrcpyState.Failed).cause.message.orEmpty().contains("Unsupported scrcpy video codec"))
            assertTrue(adb.channels.size>=2)
            assertTrue(adb.channels.all{it.closed})
        }finally{controller.stop();scope.coroutineContext[Job]?.cancelAndJoin()}
    }
    @Test fun failedSessionReplacesSurfacePreservesErrorAndAllowsRetry()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val controller=AscrcpyPreviewController(InstrumentationRegistry.getInstrumentation().targetContext,scope){
            error("Owned preview connection failure")
        }
        val current=AtomicReference<Surface?>()
        try {
            compose.setContent {
                val state by controller.state.collectAsState()
                val epoch by controller.surfaceEpoch.collectAsState()
                MirrorSurface(epoch,state !is ScrcpyState.Idle && state !is ScrcpyState.Failed,16f/9f,
                    onSurface={current.set(it)},onDestroyed=controller::stop)
            }
            compose.waitUntil(10000){current.get()?.isValid==true}
            repeat(2){
                val before=checkNotNull(current.get())
                val epoch=controller.surfaceEpoch.value
                compose.runOnUiThread{controller.start(before)}
                compose.waitUntil(10000){controller.state.value is ScrcpyState.Failed &&
                    controller.surfaceEpoch.value>epoch && !before.isValid &&
                    current.get()?.isValid==true && current.get()!==before}
                compose.waitForIdle()
                assertEquals("Owned preview connection failure",(controller.state.value as ScrcpyState.Failed).cause.message)
            }
        }finally{controller.stop();scope.coroutineContext[Job]?.cancelAndJoin()}
    }
}
