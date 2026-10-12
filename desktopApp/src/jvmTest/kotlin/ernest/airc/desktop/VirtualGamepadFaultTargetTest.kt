package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class VirtualGamepadFaultTargetTest {
    @Test fun tcpDisconnectReleasesHeldButtonAndRemovesOwnedInputDevice()=verify(false)
    @Test fun tcpDisconnectCentersMovedAxesAndRemovesOwnedInputDevice()=verify(true)
    private fun verify(axes:Boolean):Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial");assumeTrue("Set -PaircTargetSerial",!serial.isNullOrBlank())
        val target=VirtualGamepadTarget(serial!!);val runtime=RuntimeConnection()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val controller=VirtualGamepadController(scope,runtime)
        var relay:TcpFaultRelay?=null
        try {
            target.prepare();val active=TcpFaultRelay(target.port!!);relay=active
            runtime.connect("127.0.0.1",active.port)
            assertTrue(controller.start(true))
            val running=withTimeout(7000){controller.state.first{!it.starting}}
            assertTrue(running.running,running.error);val name=checkNotNull(running.deviceName)
            delay(700)
            val input=if(axes)VirtualGamepadInput(listOf("a"),.5,-.25,.75,-1.0,1,-1)else VirtualGamepadInput(buttons=listOf("a"))
            assertTrue(controller.replace(input))
            target.awaitEvent{it["deviceName"]?.jsonPrimitive?.content==name&&it["kind"]?.jsonPrimitive?.content=="key"&&it["code"]?.jsonPrimitive?.int==96&&it["action"]?.jsonPrimitive?.int==0}
            var movedAt=0L
            if(axes){
                val moved=target.awaitEvent{it["deviceName"]?.jsonPrimitive?.content==name&&it["kind"]?.jsonPrimitive?.content=="motion"&&it["z"]?.jsonPrimitive?.double?.let{value->value>.7}==true}
                assertTrue(listOf("x" to .5,"y" to -.25,"z" to .75,"rz" to -1.0,"hatX" to 1.0,"hatY" to -1.0).all{(key,value)->kotlin.math.abs(moved[key]!!.jsonPrimitive.double-value)<.02})
                movedAt=moved["eventTime"]!!.jsonPrimitive.long
            }
            active.cut();withTimeout(5000){runtime.connected.first{!it}}
            val stopped=withTimeout(7000){controller.state.first{!it.running&&!it.starting&&!it.stopping}}
            assertEquals(VirtualGamepadInput(),stopped.input)
            target.awaitEvent{it["deviceName"]?.jsonPrimitive?.content==name&&it["kind"]?.jsonPrimitive?.content=="key"&&it["code"]?.jsonPrimitive?.int==96&&it["action"]?.jsonPrimitive?.int==1}
            if(axes)target.awaitEvent{event->
                event["deviceName"]?.jsonPrimitive?.content==name&&event["kind"]?.jsonPrimitive?.content=="motion"&&
                event["eventTime"]!!.jsonPrimitive.long>movedAt&&listOf("x","y","z","rz","hatX","hatY").all{k->kotlin.math.abs(event[k]!!.jsonPrimitive.double)<.02}
            }
            target.awaitRemoved(name)
            active.restore();runtime.connect("127.0.0.1",active.port)
            assertFalse(controller.state.value.running);assertEquals(VirtualGamepadInput(),controller.state.value.input)
        }finally{controller.stop();runtime.close();scope.cancel();relay?.close();target.close()}
    }
}
