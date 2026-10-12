package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.*

@RunWith(Parameterized::class)
class TouchGesturesTargetTest(private val preferRoot:Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="root={0}")
        fun cases()=listOf(arrayOf<Any>(false),arrayOf<Any>(true))
    }
    @Test fun targetReceivesNormalizedTapDoubleTapLongPressAndSwipe():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial");assumeTrue("Set -PaircTargetSerial",!serial.isNullOrBlank())
        val host=DesktopDiscovery.tcpAdbHost(serial!!);assumeTrue("Direct target IP required",host!=null)
        val target=VirtualGamepadTarget(serial);val runtime=RuntimeConnection()
        suspend fun events()=target.shell("run-as ernest.airc.mediafixture cat files/touch-events.jsonl").lineSequence().filter{it.isNotBlank()}.map{Json.parseToJsonElement(it).jsonObject}.toList()
        suspend fun call(tool:String,args:JsonObject)=runtime.request("tool",tool,args)
        suspend fun await(predicate:(List<JsonObject>)->Boolean):List<JsonObject> =withTimeout(7000){var result=events();while(!predicate(result)){delay(50);result=events()};result}
        fun JsonObject.action()=this["action"]!!.jsonPrimitive.int
        fun near(event:JsonObject,x:Double,y:Double){
            val width=event["width"]!!.jsonPrimitive.int;val height=event["height"]!!.jsonPrimitive.int
            assertTrue(kotlin.math.abs(event["x"]!!.jsonPrimitive.double-x*(width-1))<=2)
            assertTrue(kotlin.math.abs(event["y"]!!.jsonPrimitive.double-y*(height-1))<=2)
        }
        try {
            target.prepare(".TouchActivity",preferRoot)
            val status=runtime.connect(host!!,target.runtimePort!!)
            assertEquals(if(preferRoot)0 else 2000,status.uid)
            call("remote.tap",buildJsonObject{put("x",.5);put("y",.5)})
            val tapped=await{it.count{e->e["kind"]!!.jsonPrimitive.content=="motion"&&e.action()==1}==1}
            val motions=tapped.filter{it["kind"]!!.jsonPrimitive.content=="motion"}
            assertEquals(listOf(0,1),motions.map{it.action()});motions.forEach{near(it,.5,.5)}
            val beforeDouble=tapped.size;delay(500)
            call("remote.tap",buildJsonObject{put("x",.5);put("y",.5);put("count",2)})
            val doubled=await{it.drop(beforeDouble).count{e->e["kind"]!!.jsonPrimitive.content=="motion"&&e.action()==1}==2}
            assertEquals(listOf(0,1,0,1),doubled.drop(beforeDouble).filter{it["kind"]!!.jsonPrimitive.content=="motion"}.map{it.action()})
            assertTrue(doubled.drop(beforeDouble).any{it["kind"]!!.jsonPrimitive.content=="double_tap"},"Android must recognize a double tap")
            val beforeLong=doubled.size;delay(500)
            call("remote.long_press",buildJsonObject{put("x",.5);put("y",.5);put("durationMs",700)})
            val held=await{it.drop(beforeLong).any{e->e["kind"]!!.jsonPrimitive.content=="motion"&&e.action()==1}}
            assertTrue(held.drop(beforeLong).any{it["kind"]!!.jsonPrimitive.content=="long_press"})
            val holdMotions=held.drop(beforeLong).filter{it["kind"]!!.jsonPrimitive.content=="motion"};holdMotions.forEach{near(it,.5,.5)}
            assertTrue(holdMotions.last()["eventTime"]!!.jsonPrimitive.long-holdMotions.first()["eventTime"]!!.jsonPrimitive.long>=650)
            val beforeSwipe=held.size
            call("remote.swipe",buildJsonObject{put("startX",.25);put("startY",.5);put("endX",.75);put("endY",.5);put("durationMs",300)})
            val swiped=await{it.drop(beforeSwipe).any{e->e["kind"]!!.jsonPrimitive.content=="motion"&&e.action()==1}}.drop(beforeSwipe).filter{it["kind"]!!.jsonPrimitive.content=="motion"}
            near(swiped.first(),.25,.5);near(swiped.last(),.75,.5);assertTrue(swiped.any{it.action()==2})
        }finally{runtime.close();target.close()}
    }
}
