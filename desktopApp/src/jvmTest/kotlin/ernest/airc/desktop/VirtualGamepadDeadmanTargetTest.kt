package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class VirtualGamepadDeadmanTargetTest {
    @Test fun missingHeartbeatsNeutralizeInputWhileControlConnectionStaysOpen():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial");assumeTrue("Set -PaircTargetSerial",!serial.isNullOrBlank())
        val target=VirtualGamepadTarget(serial!!);val runtime=RuntimeConnection();var id:String?=null
        try {
            target.prepare();runtime.connect("127.0.0.1",target.port!!)
            val opened=Json.parseToJsonElement(runtime.request("tool","gamepad.virtual_open")).jsonObject
            id=opened["id"]!!.jsonPrimitive.content;val name=opened["deviceName"]!!.jsonPrimitive.content
            assertEquals(2000,opened["deadmanMs"]!!.jsonPrimitive.int);delay(700)
            val input=VirtualGamepadInput(listOf("a"),.5,-.25,.75,-1.0,1,-1)
            runtime.request("tool","gamepad.virtual_update",buildJsonObject{put("id",id);put("sequence",1);put("state",Json.encodeToJsonElement(VirtualGamepadInput.serializer(),input))})
            val moved=target.awaitEvent{it["deviceName"]?.jsonPrimitive?.content==name&&it["kind"]?.jsonPrimitive?.content=="motion"&&it["z"]?.jsonPrimitive?.double?.let{value->value>.7}==true}
            val movedAt=moved["eventTime"]!!.jsonPrimitive.long
            target.awaitEvent{it["deviceName"]?.jsonPrimitive?.content==name&&it["kind"]?.jsonPrimitive?.content=="key"&&it["code"]?.jsonPrimitive?.int==96&&it["action"]?.jsonPrimitive?.int==1}
            val neutral=target.awaitEvent{event->event["deviceName"]?.jsonPrimitive?.content==name&&event["kind"]?.jsonPrimitive?.content=="motion"&&event["eventTime"]!!.jsonPrimitive.long>movedAt&&listOf("x","y","z","rz","hatX","hatY").all{k->kotlin.math.abs(event[k]!!.jsonPrimitive.double)<.02}}
            val elapsed=neutral["eventTime"]!!.jsonPrimitive.long-movedAt
            assertTrue(elapsed in 1500..5000,"Deadman event delay: $elapsed ms")
            assertTrue(runtime.connected.value)
            assertTrue(target.shell("cat /proc/bus/input/devices").contains(name),"Deadman should neutralize without disconnecting the lease")
            runtime.request("tool","gamepad.virtual_close",buildJsonObject{put("id",id)})
            id=null;target.awaitRemoved(name)
        }finally{ id?.let{runCatching{runtime.request("tool","gamepad.virtual_close",buildJsonObject{put("id",it)})}};runtime.close();target.close() }
    }
}
