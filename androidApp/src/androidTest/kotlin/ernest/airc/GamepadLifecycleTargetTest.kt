package ernest.airc

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Actual phone background -> owned root binary -> owned Android key receiver. */
@RunWith(Parameterized::class)
class GamepadLifecycleTargetTest(private val control:String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}")
        fun controls()=listOf(arrayOf("button-a"),arrayOf("left-stick"),arrayOf("right-stick"),arrayOf("combined-snapshot"),arrayOf("combined-touch"))
    }
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun backgroundReleasesInputAndRemovesVirtualDevice():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val host=args.getString("fileTargetHost");val port=args.getString("fileTargetPort")?.toIntOrNull()
        assumeTrue("Explicit owned root target required",host!=null&&port!=null)
        val observer=RuntimeConnection();lateinit var model:RemoteViewModel
        var initialized=false;var id:String?=null
        suspend fun shell(command:String)=observer.request("tool","device.shell",buildJsonObject{put("command",command)})
        suspend fun await(stage:String,condition:()->Boolean){try{withTimeout(15000){while(!condition())delay(50)}}catch(error:TimeoutCancellationException){throw AssertionError("Gamepad timeout: $stage",error)}}
        suspend fun event(name:String,predicate:(JsonObject)->Boolean):JsonObject=withTimeout(10000){
            var found:JsonObject?=null
            while(found==null){found=shell("run-as ernest.airc.mediafixture cat files/gamepad-events.jsonl").lineSequence().filter{it.isNotBlank()}.map{Json.parseToJsonElement(it).jsonObject}.firstOrNull{
                it["deviceName"]?.jsonPrimitive?.content==name && predicate(it)
            };if(found==null)delay(50)}
            found
        }
        fun keyMatches(value:JsonObject,action:Int,code:Int=96)=value["kind"]?.jsonPrimitive?.content=="key" && value["code"]?.jsonPrimitive?.int==code && value["action"]?.jsonPrimitive?.int==action
        try {
            id=observer.connect(host!!,port!!).deviceId
            assertEquals("0",shell("id -u").trim())
            assertEquals("ready",shell("run-as ernest.airc.mediafixture sh -c 'test -f files/gamepad-ready && echo ready'").trim())
            compose.runOnUiThread{model=ViewModelProvider(compose.activity)[RemoteViewModel::class.java];initialized=true;model.discovered(DiscoveredService("Owned root gamepad lifecycle",host,port,emptyMap()))}
            await("connection"){model.state.value.connected&&!model.state.value.busy}
            val before=compose.activity
            val requiresRotation=before.resources.configuration.orientation!=Configuration.ORIENTATION_LANDSCAPE
            compose.onNodeWithTag("open-remote-types").performClick()
            compose.onNodeWithTag("remote-type-gamepad").performScrollTo().performClick()
            compose.waitUntil(15000){(!requiresRotation||compose.activity!==before) && compose.activity.lifecycle.currentState==Lifecycle.State.RESUMED && compose.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
            compose.waitForIdle()
            compose.onNodeWithText("开始持续手柄").performScrollTo().performClick()
            await("virtual device"){model.virtualGamepad.state.value.running&&!model.virtualGamepad.state.value.starting || model.virtualGamepad.state.value.error!=null}
            assertNull(model.virtualGamepad.state.value.error)
            val name=checkNotNull(model.virtualGamepad.state.value.deviceName)
            delay(700)
            val tag=when(control){"button-a"->"virtual-button-a";"left-stick"->"virtual-stick-left";else->"virtual-stick-right"}
            val input=compose.onNodeWithTag(tag)
            val combined=control=="combined-snapshot"
            val multiTouch=control=="combined-touch"
            if(combined)compose.runOnUiThread{assertTrue(model.virtualGamepad.replace(VirtualGamepadInput(
                buttons=listOf("a","b"),leftX=.9,leftY=-.9,rightX=.9,rightY=-.9,hatX=1,hatY=-1)))}
            else if(multiTouch){
                compose.onNodeWithTag("virtual-stick-left").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("virtual-stick-right").assertIsDisplayed()
                compose.onNodeWithTag("virtual-button-a").assertIsDisplayed()
                val left=compose.onNodeWithTag("virtual-stick-left").fetchSemanticsNode().boundsInRoot
                val right=compose.onNodeWithTag("virtual-stick-right").fetchSemanticsNode().boundsInRoot
                val button=compose.onNodeWithTag("virtual-button-a").fetchSemanticsNode().boundsInRoot
                compose.onRoot().performTouchInput{
                    down(0,androidx.compose.ui.geometry.Offset(left.right-1,left.center.y))
                    down(1,androidx.compose.ui.geometry.Offset(right.left+1,right.center.y))
                    down(2,button.center)
                }
                compose.runOnUiThread{
                    val state=model.virtualGamepad.state.value.input
                    assertTrue("Left touch state: $state",state.leftX>.8)
                    assertTrue("Right touch state: $state",state.rightX<-.8)
                    assertTrue("Button touch state: $state","a" in state.buttons)
                }
            }
            else input.performScrollTo().performTouchInput{down(if(control=="button-a")center else centerRight)}
            val axis=if(control=="left-stick")"x"else"z"
            val pressed=event(name){if(control=="button-a")keyMatches(it,0)else it["kind"]?.jsonPrimitive?.content=="motion" &&
                if(combined)it["x"]!!.jsonPrimitive.double>.8 && it["y"]!!.jsonPrimitive.double<-.8 &&
                    it["z"]!!.jsonPrimitive.double>.8 && it["rz"]!!.jsonPrimitive.double<-.8 &&
                    it["hatX"]!!.jsonPrimitive.double>.8 && it["hatY"]!!.jsonPrimitive.double<-.8
                else if(multiTouch)it["x"]!!.jsonPrimitive.double>.8 && it["z"]!!.jsonPrimitive.double<-.8
                else it[axis]!!.jsonPrimitive.double>.8}
            if(combined){event(name){keyMatches(it,0)};event(name){keyMatches(it,0,97)}}
            if(multiTouch)event(name){keyMatches(it,0)}

            compose.runOnUiThread{assertTrue(compose.activity.moveTaskToBack(true))}
            await("background"){!compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)}
            await("input stopped"){!model.virtualGamepad.state.value.running&&!model.virtualGamepad.state.value.stopping}
            event(name){if(control=="button-a")keyMatches(it,1)else
                it["kind"]?.jsonPrimitive?.content=="motion" && it["eventTime"]!!.jsonPrimitive.long>pressed["eventTime"]!!.jsonPrimitive.long &&
                listOf("x","y","z","rz","hatX","hatY").all{axisName->kotlin.math.abs(it[axisName]!!.jsonPrimitive.double)<.02}}
            if(combined){event(name){keyMatches(it,1)};event(name){keyMatches(it,1,97)}}
            if(multiTouch)event(name){keyMatches(it,1)}

            withTimeout(10000){while(shell("cat /proc/bus/input/devices").contains(name))delay(50)}
            assertEquals(VirtualGamepadInput(),model.virtualGamepad.state.value.input)
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            await("foreground"){compose.activity.lifecycle.currentState==Lifecycle.State.RESUMED}
            compose.waitForIdle()
            if(multiTouch)compose.onRoot().performTouchInput{up(2);up(1);up(0)}
            else if(!combined)input.performTouchInput{up()}
            assertFalse(model.virtualGamepad.state.value.running)
            compose.onNodeWithText("开始持续手柄").performScrollTo().assertIsEnabled()
        }finally{if(initialized)compose.runOnUiThread{model.virtualGamepad.stop();model.disconnect()};observer.close();id?.let{DeviceStore(instrumentation.targetContext).remove(it)}}
    }
}
