package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class VirtualGamepadSequenceTargetTest {
    @Test fun rejectedUpdatesDoNotConsumeNextSequence():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial");assumeTrue("Set -PaircTargetSerial",!serial.isNullOrBlank())
        val target=VirtualGamepadTarget(serial!!);val runtime=RuntimeConnection();var id:String?=null
        fun args(sequence:Long,input:VirtualGamepadInput)=buildJsonObject{put("id",id);put("sequence",sequence);put("state",Json.encodeToJsonElement(VirtualGamepadInput.serializer(),input))}
        try {
            target.prepare();runtime.connect("127.0.0.1",target.port!!)
            val opened=Json.parseToJsonElement(runtime.request("tool","gamepad.virtual_open")).jsonObject
            id=opened["id"]!!.jsonPrimitive.content;val name=opened["deviceName"]!!.jsonPrimitive.content;delay(700)
            runtime.request("tool","gamepad.virtual_update",args(1,VirtualGamepadInput(buttons=listOf("a"))))
            target.awaitEvent{it["deviceName"]?.jsonPrimitive?.content==name&&it["kind"]?.jsonPrimitive?.content=="key"&&it["code"]?.jsonPrimitive?.int==96&&it["action"]?.jsonPrimitive?.int==0}
            for(sequence in listOf(1L,0L,4294967296L))assertFails{runtime.request("tool","gamepad.virtual_update",args(sequence,VirtualGamepadInput()))}
            assertFails{runtime.request("tool","gamepad.virtual_update",args(2,VirtualGamepadInput(buttons=listOf("power"))))}
            val accepted=Json.parseToJsonElement(runtime.request("tool","gamepad.virtual_update",args(2,VirtualGamepadInput()))).jsonObject
            assertEquals(2,accepted["sequence"]!!.jsonPrimitive.int)
            target.awaitEvent{it["deviceName"]?.jsonPrimitive?.content==name&&it["kind"]?.jsonPrimitive?.content=="key"&&it["code"]?.jsonPrimitive?.int==96&&it["action"]?.jsonPrimitive?.int==1}
            runtime.request("tool","gamepad.virtual_close",buildJsonObject{put("id",id)});id=null;target.awaitRemoved(name)
        }finally{id?.let{runCatching{runtime.request("tool","gamepad.virtual_close",buildJsonObject{put("id",it)})}};runtime.close();target.close()}
    }
}
