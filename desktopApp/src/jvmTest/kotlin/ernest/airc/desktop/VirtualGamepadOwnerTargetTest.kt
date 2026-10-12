package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class VirtualGamepadOwnerTargetTest {
    @Test fun otherConnectionCannotUpdateOrCloseOwnedLease():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial");assumeTrue("Set -PaircTargetSerial",!serial.isNullOrBlank())
        val target=VirtualGamepadTarget(serial!!);val owner=RuntimeConnection();val other=RuntimeConnection();var id:String?=null
        fun update(sequence:Int,input:VirtualGamepadInput)=buildJsonObject{put("id",id);put("sequence",sequence);put("state",Json.encodeToJsonElement(VirtualGamepadInput.serializer(),input))}
        try {
            target.prepare();owner.connect("127.0.0.1",target.port!!);other.connect("127.0.0.1",target.port!!)
            val opened=Json.parseToJsonElement(owner.request("tool","gamepad.virtual_open")).jsonObject
            id=opened["id"]!!.jsonPrimitive.content;val name=opened["deviceName"]!!.jsonPrimitive.content
            delay(700)
            owner.request("tool","gamepad.virtual_update",update(1,VirtualGamepadInput(buttons=listOf("a"))))
            target.awaitEvent{it["deviceName"]?.jsonPrimitive?.content==name&&it["kind"]?.jsonPrimitive?.content=="key"&&it["code"]?.jsonPrimitive?.int==96&&it["action"]?.jsonPrimitive?.int==0}
            assertFails{other.request("tool","gamepad.virtual_update",update(2,VirtualGamepadInput()))}
            assertFails{other.request("tool","gamepad.virtual_close",buildJsonObject{put("id",id)})}
            assertTrue(target.shell("cat /proc/bus/input/devices").contains(name))
            owner.request("tool","gamepad.virtual_update",update(2,VirtualGamepadInput()))
            target.awaitEvent{it["deviceName"]?.jsonPrimitive?.content==name&&it["kind"]?.jsonPrimitive?.content=="key"&&it["code"]?.jsonPrimitive?.int==96&&it["action"]?.jsonPrimitive?.int==1}
            val closed=Json.parseToJsonElement(owner.request("tool","gamepad.virtual_close",buildJsonObject{put("id",id)})).jsonObject
            assertTrue(closed["closed"]!!.jsonPrimitive.boolean);id=null;target.awaitRemoved(name)
        }finally{id?.let{runCatching{owner.request("tool","gamepad.virtual_close",buildJsonObject{put("id",it)})}};other.close();owner.close();target.close()}
    }
}
