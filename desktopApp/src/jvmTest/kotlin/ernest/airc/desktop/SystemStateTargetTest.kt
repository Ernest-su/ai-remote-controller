package ernest.airc.desktop
import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class SystemStateTargetTest {
    @Test fun rootStateQueryReportsObservationsWithoutExecutingActions():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial");assumeTrue("Set -PaircTargetSerial",!serial.isNullOrBlank())
        val target=ShellRuntimeTarget(serial!!,true);val runtime=RuntimeConnection()
        try {
            target.prepare();val port=checkNotNull(target.port);runtime.connect("127.0.0.1",port)
            val state=Json.parseToJsonElement(runtime.request("tool","device.system_state")).jsonObject
            assertTrue(state.getValue("readOnly").jsonPrimitive.boolean)
            assertTrue(state.getValue("available").jsonPrimitive.boolean)
            assertEquals(0,state.getValue("runtimeUid").jsonPrimitive.int)
            assertEquals(0,state.getValue("userId").jsonPrimitive.int)
            assertEquals("android_framework",state.getValue("source").jsonPrimitive.content)
            assertTrue(state.getValue("elapsedRealtimeMs").jsonPrimitive.long>=0)
            for(name in listOf("interactive","keyguardLocked","deviceLocked","deviceSecure")){
                val value=state.getValue(name);assertTrue(value==JsonNull || value.jsonPrimitive.booleanOrNull!=null)
            }
            assertFails{runtime.request("tool","device.system_state",buildJsonObject{put("action","lock")})}
            assertEquals(0,runtime.status("127.0.0.1",port).uid)
            println("Read-only root system observations: $state")
        }finally{runtime.close();target.close()}
    }
}
