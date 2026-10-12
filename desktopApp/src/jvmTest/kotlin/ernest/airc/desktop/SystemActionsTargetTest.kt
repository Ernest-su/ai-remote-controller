package ernest.airc.desktop
import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Only permission discovery, proposal preparation and rejection. Never accepts an action. */
class SystemActionsTargetTest {
    @Test fun rootFrameworkProbeAndProposalOwnershipDoNotExecuteSystemActions():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial");assumeTrue("Set -PaircTargetSerial",!serial.isNullOrBlank())
        val fixture=ShellRuntimeTarget(serial!!,true);val owner=RuntimeConnection();val other=RuntimeConnection()
        try {
            fixture.prepare();val port=checkNotNull(fixture.port)
            owner.connect("127.0.0.1",port);other.connect("127.0.0.1",port)
            val cap=Json.parseToJsonElement(owner.request("tool","device.system_capabilities")).jsonObject
            assertEquals(0,cap.getValue("runtimeUid").jsonPrimitive.int)
            assertTrue(cap.getValue("readOnly").jsonPrimitive.boolean)
            assertTrue(cap.getValue("permissionProbeAvailable").jsonPrimitive.boolean)
            assertTrue(cap.getValue("executionImplemented").jsonPrimitive.boolean)
            assertFalse(cap.getValue("supported").jsonPrimitive.boolean)
            println("Owned root system probe: $cap")
            val eligible=cap.getValue("devicePowerGranted").jsonPrimitive.boolean && cap.getValue("lockInterfacePresent").jsonPrimitive.boolean
            if(!eligible){assertFails{owner.request("tool","device.system_action",buildJsonObject{put("action","lock")})};return@runBlocking}
            val proposal=Json.decodeFromString<SystemActionProposal>(owner.request("tool","device.system_action",buildJsonObject{put("action","lock")}))
            assertTrue(validSystemProposal(proposal))
            assertEquals("[]",other.request("tool","device.system_pending"))
            assertFails{other.request("approve_system",arguments=buildJsonObject{put("id",proposal.id);put("accepted",false)})}
            val rejected=Json.parseToJsonElement(owner.request("approve_system",arguments=buildJsonObject{put("id",proposal.id);put("accepted",false)})).jsonObject
            assertTrue(rejected.getValue("rejected").jsonPrimitive.boolean)
            assertEquals("[]",owner.request("tool","device.system_pending"))
            val status=owner.status("127.0.0.1",port)
            assertEquals(0,status.uid)
        }finally{owner.close();other.close();fixture.close()}
    }
}
