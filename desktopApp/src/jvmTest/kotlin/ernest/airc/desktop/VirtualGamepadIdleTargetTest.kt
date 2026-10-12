package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class VirtualGamepadIdleTargetTest {
    @Test fun idleLeaseExpiresWithoutClosingControlConnection():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial");assumeTrue("Set -PaircTargetSerial",!serial.isNullOrBlank())
        val target=VirtualGamepadTarget(serial!!);val runtime=RuntimeConnection();var id:String?=null
        try {
            target.prepare();runtime.connect("127.0.0.1",target.port!!)
            val startedAt=System.nanoTime()
            val opened=Json.parseToJsonElement(runtime.request("tool","gamepad.virtual_open")).jsonObject
            id=opened["id"]!!.jsonPrimitive.content;val name=opened["deviceName"]!!.jsonPrimitive.content
            assertEquals(30,opened["idleCloseSeconds"]!!.jsonPrimitive.int)
            assertTrue(target.shell("cat /proc/bus/input/devices").contains(name))
            withTimeout(35000){while(target.shell("cat /proc/bus/input/devices").contains(name))delay(100)}
            val elapsed=(System.nanoTime()-startedAt)/1_000_000
            assertTrue(elapsed in 29000..35000,"Expected idle lease expiry near thirty seconds: $elapsed ms")
            assertTrue(runtime.connected.value)
            assertFails{runtime.request("tool","gamepad.virtual_update",buildJsonObject{put("id",id);put("sequence",1);put("state",Json.encodeToJsonElement(VirtualGamepadInput.serializer(),VirtualGamepadInput()))})}
            id=null
        }finally{id?.let{runCatching{runtime.request("tool","gamepad.virtual_close",buildJsonObject{put("id",it)})}};runtime.close();target.close()}
    }
}
