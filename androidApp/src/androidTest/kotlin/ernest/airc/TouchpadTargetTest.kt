package ernest.airc

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Actual phone UI -> direct LAN shell service -> owned Android MotionEvent receiver. */
class TouchpadTargetTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun phoneTouchpadProducesTargetTapDoubleTapHoldAndSwipe()=runBlocking{
        val instrumentation=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
        val host=args.getString("fileTargetHost");val port=args.getString("fileTargetPort")?.toIntOrNull()
        assumeTrue("Explicit independent touch target required",host!=null&&port!=null)
        val originalOrientation=compose.activity.requestedOrientation
        val observer=RuntimeConnection();lateinit var model:RemoteViewModel;var initialized=false;var id:String?=null
        suspend fun events()=observer.request("tool","device.shell",buildJsonObject{put("command","run-as ernest.airc.mediafixture cat files/touch-events.jsonl")}).lineSequence().filter{it.isNotBlank()}.map{Json.parseToJsonElement(it).jsonObject}.toList()
        fun JsonObject.kind()=this["kind"]!!.jsonPrimitive.content
        fun JsonObject.action()=this["action"]!!.jsonPrimitive.int
        var stage="connect"
        suspend fun await(condition:()->Boolean){try{withTimeout(15000){while(!condition())delay(50)}}catch(error:TimeoutCancellationException){throw AssertionError("Timed out at $stage",error)}}
        suspend fun released(since:Int,count:Int):List<JsonObject> =try{withTimeout(15000){var data=events().drop(since);while(data.count{it.kind()=="motion"&&it.action()==1}<count){delay(50);data=events().drop(since)};data}}catch(error:TimeoutCancellationException){throw AssertionError("No target release at $stage",error)}
        suspend fun idle(){await{!model.state.value.touchBusy};compose.waitForIdle();delay(500)}
        fun near(event:JsonObject,x:Double,y:Double){
            assertTrue(kotlin.math.abs(event["x"]!!.jsonPrimitive.double-x*(event["width"]!!.jsonPrimitive.int-1))<=3)
            assertTrue(kotlin.math.abs(event["y"]!!.jsonPrimitive.double-y*(event["height"]!!.jsonPrimitive.int-1))<=3)
        }
        try{
            id=observer.connect(host!!,port!!).deviceId
            assertEquals(2000,observer.session.value?.let{observer.request("tool","device.shell",buildJsonObject{put("command","id -u")}).trim().toInt()})
            compose.runOnUiThread{model=ViewModelProvider(compose.activity)[RemoteViewModel::class.java];initialized=true;model.discovered(DiscoveredService("Owned phone touch target",host,port,emptyMap()))}
            await{model.state.value.connected&&!model.state.value.busy}
            compose.onNodeWithTag("open-remote-types").performClick();compose.onNodeWithTag("remote-type-touchpad").performScrollTo().performClick()
            val pad=compose.onNodeWithTag("touchpad-area").performScrollTo()
            var count=events().size
            stage="tap";pad.assertIsEnabled();pad.performTouchInput{click(center)};compose.mainClock.advanceTimeBy(400)
            val tap=released(count,1).filter{it.kind()=="motion"};assertEquals(listOf(0,1),tap.map{it.action()});tap.forEach{near(it,.5,.5)};idle()
            count=events().size;stage="double tap";pad.performTouchInput{doubleClick(center)};compose.mainClock.advanceTimeBy(400)
            val double=released(count,2);assertTrue(double.any{it.kind()=="double_tap"});idle()
            count=events().size;stage="long press";pad.performTouchInput{longClick(center)}
            val hold=released(count,1);assertTrue(hold.any{it.kind()=="long_press"});idle()
            count=events().size;stage="swipe";pad.performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.25f,center.y),androidx.compose.ui.geometry.Offset(width*.75f,center.y),300)}
            val swipe=released(count,1).filter{it.kind()=="motion"};assertTrue(swipe.any{it.action()==2});near(swipe.first(),.25,.5);near(swipe.last(),.75,.5);idle()
            stage="rotation";android.util.Log.i("AircTouchFixture","before rotation")
            val before=compose.activity;val orientation=before.resources.configuration.orientation
            compose.runOnUiThread{before.requestedOrientation=if(orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT)android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            android.util.Log.i("AircTouchFixture","rotation requested")
            await{compose.activity!==before&&compose.activity.resources.configuration.orientation!=orientation}
            android.util.Log.i("AircTouchFixture","new activity ready")
            compose.waitForIdle();android.util.Log.i("AircTouchFixture","composition idle");compose.onNodeWithTag("touchpad-page").assertIsDisplayed()
            count=events().size;stage="rotated tap"
            val rotatedPad=compose.onNodeWithTag("touchpad-area").performScrollTo()
            val node=rotatedPad.fetchSemanticsNode()
            assertTrue("Rotated touch area must remain entirely visible",node.boundsInRoot.height>=node.size.height-1)
            rotatedPad.performTouchInput{click(center)}
            compose.mainClock.advanceTimeBy(400)
            android.util.Log.i("AircTouchFixture","rotated tap submitted")
            val rotated=released(count,1).filter{it.kind()=="motion"};assertEquals(listOf(0,1),rotated.map{it.action()});rotated.forEach{near(it,.5,.5)}
        }finally{observer.close();if(initialized)compose.runOnUiThread{model.disconnect()};compose.runOnUiThread{compose.activity.requestedOrientation=originalOrientation};id?.let{DeviceStore(instrumentation.targetContext).remove(it)}}
    }
}
