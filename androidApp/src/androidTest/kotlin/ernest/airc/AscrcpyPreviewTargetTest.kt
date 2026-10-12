package ernest.airc

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.PixelCopy
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.test.platform.app.InstrumentationRegistry
import ernest.ascrcpy.scrcpy.ScrcpyState
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*

class AscrcpyPreviewTargetTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun cancelDuringLateAdbConnectionDoesNotStartServer()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val host=args.getString("ascrcpyTargetHost")
        val port=args.getString("ascrcpyTargetPort")?.toIntOrNull()
        assumeTrue("Explicit target ADB endpoint required",host!=null&&port!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        lateinit var late:AdbSession
        val observer=AdbConnector(context).tcp(host!!,port!!)
        val command="find /data/local/tmp -maxdepth 1 -name 'airc-scrcpy-*.jar' -print"
        val baseline=observer.client.shell(command).text().trim()
        val controller=AscrcpyPreviewController(context,scope){withContext(NonCancellable){
            entered.complete(Unit);release.await()
            AdbConnector(context).tcp(host,port).also{late=it}
        }}
        try {
            compose.setContent{AndroidView(factory={SurfaceView(it).apply{holder.addCallback(object:SurfaceHolder.Callback{
                override fun surfaceCreated(h:SurfaceHolder){controller.start(h.surface)}
                override fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,hgt:Int){}
                override fun surfaceDestroyed(h:SurfaceHolder){controller.stop()}
            })}},modifier=Modifier.fillMaxSize())}
            withTimeout(5000){entered.await()}
            val running=scope.coroutineContext[Job]!!.children.single()
            controller.stop();release.complete(Unit)
            withTimeout(15000){running.join()}
            assertEquals(ScrcpyState.Idle,controller.state.value)
            assertEquals(baseline,observer.client.shell(command).text().trim())
            assertFalse("Late ADB session must be closed",late.client.state.value is ernest.ascrcpy.adb.AdbConnectionState.Connected)
        }finally{release.complete(Unit);controller.stop();scope.coroutineContext[Job]?.cancelAndJoin();observer.close()}
    }
    @Test fun staticTargetRendersAndOwnedServerStops()=verifyRenderedTarget(false)
    @Test fun movingTargetChangesActualSurfacePixelsAndOwnedServerStops()=verifyRenderedTarget(true)
    @Test fun targetPortraitReconfiguresDecoderAndRendersNewGeometry()=verifyRenderedTarget(true,true)
    private fun verifyRenderedTarget(animated:Boolean,rotate:Boolean=false):Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val host=args.getString("ascrcpyTargetHost")
        val port=args.getString("ascrcpyTargetPort")?.toIntOrNull()
        assumeTrue("Explicit target ADB endpoint required",host!=null&&port!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val controller=AscrcpyPreviewController(context,scope){AdbConnector(context).tcp(host!!,port!!)}
        val before=AdbConnector(context).tcp(host!!,port!!)
        fun command()="find /data/local/tmp -maxdepth 1 -name 'airc-scrcpy-*.jar' -print"
        val baseline=before.client.shell(command()).text().trim()
        suspend fun serverPids():Set<Int> = before.client.shell("ps -A -o PID,ARGS").text().lineSequence()
            .filter{it.contains("com.genymobile.scrcpy.Server")}
            .mapNotNull{it.trim().substringBefore(' ').toIntOrNull()}.toSet()
        val baselinePids=serverPids()
        lateinit var view:SurfaceView
        var originalPolicy:String?=null
        var policyChanged=false
        try {
            if(rotate){
                originalPolicy=before.client.shell("wm fixed-to-user-rotation").text().trim()
                check(originalPolicy in listOf("enabled","disabled","default")){"Unknown rotation policy cannot be restored"}
                policyChanged=true
                before.client.shell("wm fixed-to-user-rotation disabled").text()
                check(before.client.shell("wm fixed-to-user-rotation").text().trim()=="disabled")
            }

            val activity=if(animated)".ColorActivity"else".TouchActivity"
            val started=before.client.shell("am start -S -W -n ernest.airc.mediafixture/$activity").text()
            assertTrue("Owned fixture start failed: $started",started.contains("Status: ok"))
            delay(700)
            compose.setContent{AndroidView(factory={SurfaceView(it).apply{view=this;holder.addCallback(object:SurfaceHolder.Callback{
                override fun surfaceCreated(h:SurfaceHolder){controller.start(h.surface)}
                override fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,hgt:Int){}
                override fun surfaceDestroyed(h:SurfaceHolder){controller.stop()}
            })}},modifier=Modifier.fillMaxSize())}
            try{withTimeout(20000){while((controller.state.value as? ScrcpyState.Streaming)?.renderedFrames?.let{it>0}!=true){
                (controller.state.value as? ScrcpyState.Failed)?.let{throw AssertionError("scrcpy failed",it.cause)}
                delay(50)
            }}}catch(error:TimeoutCancellationException){throw AssertionError("Preview timeout: ${controller.state.value}",error)}
            val streaming=controller.state.value as ScrcpyState.Streaming
            assertTrue(streaming.videoSize.width>0);assertTrue(streaming.videoSize.height>0)
            if(rotate){
                assertTrue("Initial fixture must be landscape",streaming.videoSize.width>streaming.videoSize.height)
                val changed=before.client.shell("am start -W --activity-single-top -n ernest.airc.mediafixture/.ColorActivity --es orientation portrait").text()
                assertTrue(changed.contains("Status: ok"))
                withTimeout(15000){while(true){
                    val value=controller.state.value
                    if(value is ScrcpyState.Failed)throw AssertionError("Rotation preview failed",value.cause)
                    if(value is ScrcpyState.Streaming && value.videoSize.height>value.videoSize.width && value.renderedFrames>streaming.renderedFrames)break
                    delay(50)
                }}
            }
            val ownedPids=serverPids()-baselinePids
            assertTrue("The preview must own a running scrcpy server process",ownedPids.isNotEmpty())
            val pixels=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
            try {
                withTimeout(5000){
                    while(true){
                        val result=CompletableDeferred<Int>()
                        compose.runOnUiThread{PixelCopy.request(view,pixels,{result.complete(it)},Handler(Looper.getMainLooper()))}
                        if(result.await()==PixelCopy.SUCCESS)break
                        delay(50)
                    }
                }
                if(animated){
                    val samples=mutableSetOf<Int>()
                    repeat(12){
                        delay(150)
                        val copied=CompletableDeferred<Int>()
                        compose.runOnUiThread{PixelCopy.request(view,pixels,{copied.complete(it)},Handler(Looper.getMainLooper()))}
                        assertEquals(PixelCopy.SUCCESS,withTimeout(5000){copied.await()})
                        val values=IntArray(128*128)
                        pixels.getPixels(values,0,128,0,0,128,128)
                        samples.add(values.contentHashCode())
                        val top=pixels.getPixel(64,32);val bottom=pixels.getPixel(64,96)
                        assertTrue("Moving fixture cyan top",Color.red(top)<30&&Color.green(top)>220&&Color.blue(top)>220)
                        assertTrue("Moving fixture magenta bottom: ${Integer.toHexString(bottom)}",Color.red(bottom)>220&&Color.green(bottom)<50&&Color.blue(bottom)>220)
                    }
                    assertTrue("Actual Surface pixels must change across samples: ${samples.size}",samples.size>=3)
                    assertTrue((controller.state.value as ScrcpyState.Streaming).renderedFrames>streaming.renderedFrames+3)
                }else{
                    var matches=0
                    for(y in 48..79)for(x in 48..79){val color=pixels.getPixel(x,y)
                        if(kotlin.math.abs(Color.red(color)-21)<=20 && kotlin.math.abs(Color.green(color)-101)<=20 && kotlin.math.abs(Color.blue(color)-192)<=20)matches++
                    }
                    assertTrue("Actual preview pixels must match the static blue target fixture: $matches/1024 center=${Integer.toHexString(pixels.getPixel(64,64))}",matches>=950)
                }
            }finally{pixels.recycle()}
            controller.stop()
            withTimeout(10000){while(before.client.shell(command()).text().trim()!=baseline)delay(100)}
            withTimeout(10000){while(serverPids().any{it in ownedPids})delay(100)}
            assertEquals(ScrcpyState.Idle,controller.state.value)
        }finally{
            controller.stop();scope.coroutineContext[Job]?.cancelAndJoin()
            try{
                try{if(animated)before.client.shell("am start -W -n ernest.airc.mediafixture/.TouchActivity").text()}
                finally{if(policyChanged){
                    before.client.shell("wm fixed-to-user-rotation ${checkNotNull(originalPolicy)}").text()
                    check(before.client.shell("wm fixed-to-user-rotation").text().trim()==originalPolicy){"Rotation policy restoration failed"}
                }}
            }finally{before.close()}
        }
    }
}
