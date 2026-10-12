package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.*

class GamepadTargetTest {
    @Test fun finiteButtonsAxesAndDisconnectHaveActualMatchingReleaseEvents(): Unit = runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned input receiver",!serial.isNullOrBlank())
        val adb=HostAdb();val pkg="ernest.airc.mediafixture"
        check(adb.command("-s",serial!!,"shell","pm list packages --user all $pkg").lines().none{it=="package:$pkg"}){"Refusing to replace existing fixture"}
        val apk=File(checkNotNull(System.getProperty("airc.mediaFixtureApk")));assertTrue(apk.isFile)
        val target=ShellRuntimeTarget(serial);val runtime=RuntimeConnection();val controller=GamepadController(this,runtime);var installed=false
        suspend fun events()=adb.command("-s",serial,"shell","run-as $pkg cat files/gamepad-events.jsonl").lineSequence().filter{it.isNotBlank()}.map{Json.parseToJsonElement(it).jsonObject}.toList()
        fun JsonObject.int(key:String)=getValue(key).jsonPrimitive.int
        fun JsonObject.long(key:String)=getValue(key).jsonPrimitive.long
        try {
            target.prepare()
            val port=checkNotNull(target.port)
            check(adb.command("-s",serial,"install",apk.absolutePath).contains("Success"));installed=true
            adb.command("-s",serial,"shell","am start -W -n $pkg/.GamepadActivity")
            withTimeout(5000){while(runCatching{adb.command("-s",serial,"shell","run-as $pkg test -f files/gamepad-ready")}.isFailure)delay(50)}
            runtime.connect("127.0.0.1",port)
            assertTrue(controller.pulse(GamepadPulse(listOf("a","r1"),0.5,-0.75,300),true))
            val done=withTimeout(15000){controller.state.first{!it.busy}};assertNull(done.error)
            var observed=emptyList<JsonObject>()
            val first=try {withTimeout(5000){observed=events();while(observed.size<6){delay(100);observed=events()};observed}}
            catch(error: Exception){throw AssertionError("Owned fixture event journal: $observed",error)}
            for(code in listOf(96,103)) {
                val keys=first.filter{it["kind"]!!.jsonPrimitive.content=="key"&&it.int("code")==code}
                assertEquals(listOf(0,1),keys.map{it.int("action")});assertTrue(keys.all{it.int("source")==1025&&it.int("deviceId")==-1&&it.int("repeat")==0})
                assertEquals(keys[0].long("downTime"),keys[1].long("downTime"));assertTrue(keys[1].long("eventTime")-keys[0].long("eventTime") in 300..1500)
            }
            val motion=first.filter{it["kind"]!!.jsonPrimitive.content=="motion"};assertEquals(2,motion.size)
            assertTrue(motion.all{it.int("source")==16777232&&it.int("action")==2&&it.int("deviceId")==-1})
            assertEquals(0.5,motion[0]["x"]!!.jsonPrimitive.double);assertEquals(-0.75,motion[0]["y"]!!.jsonPrimitive.double)
            assertEquals(0.0,motion[1]["x"]!!.jsonPrimitive.double);assertEquals(0.0,motion[1]["y"]!!.jsonPrimitive.double)
            assertFails{runtime.request("tool","gamepad.pulse",buildJsonObject{put("buttons",buildJsonArray{add("power")})})}
            assertEquals(first.size,events().size)

            val pending=async{runCatching{runtime.request("tool","gamepad.pulse",buildJsonObject{put("buttons",buildJsonArray{add("b")});put("holdMs",1000)})}}
            withTimeout(10000){while(events().none{it["kind"]!!.jsonPrimitive.content=="key"&&it.int("code")==97&&it.int("action")==0})delay(50)}
            runtime.disconnect();assertTrue(pending.await().isFailure)
            val released=withTimeout(7000){var value=events();while(value.none{it["kind"]!!.jsonPrimitive.content=="key"&&it.int("code")==97&&it.int("action")==1}){delay(100);value=events()};value}
            val pair=released.filter{it["kind"]!!.jsonPrimitive.content=="key"&&it.int("code")==97}
            assertEquals(listOf(0,1),pair.map{it.int("action")});assertEquals(pair[0].long("downTime"),pair[1].long("downTime"));assertTrue(pair[1].long("eventTime")-pair[0].long("eventTime")>=1000)
            runtime.connect("127.0.0.1",port);assertTrue(controller.reset(true))
            assertNull(withTimeout(15000){controller.state.first{!it.busy}}.error)
            val last=events().last();assertEquals("motion",last["kind"]!!.jsonPrimitive.content);assertEquals(0.0,last["x"]!!.jsonPrimitive.double);assertEquals(0.0,last["y"]!!.jsonPrimitive.double)
        } finally {
            controller.clear();runtime.close()
            try{if(installed)check(adb.command("-s",serial,"uninstall",pkg).contains("Success"))}
            finally{target.close()}
        }
    }
}
