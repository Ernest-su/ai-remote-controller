package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.*

/** Uses the production shared controller against an isolated owned root runtime and Activity. */
class VirtualGamepadTargetTest {
    @Test fun sharedControllerHeartbeatsAxesAndStopReachTheAndroidReceiver(): Unit = runBlocking {
        val serial = System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned continuous gamepad integration", !serial.isNullOrBlank())
        val adb = HostAdb(); val pkg = "ernest.airc.mediafixture"
        suspend fun shell(command: String) = adb.command("-s",serial!!,"shell",command)
        check(shell("pm list packages --user all $pkg").lines().none { it=="package:$pkg" }) { "Refusing to replace existing fixture" }
        val directory = "/data/local/tmp/airc-jvm-gamepad-${java.util.UUID.randomUUID()}"
        val binary = "$directory/airc-runtime"
        val privilege = RuntimePrivilege.detect(true,directory,::shell)
        check(privilege.useRoot) { "Verified root manager required" }
        suspend fun root(command: String) = shell(privilege.command(command))
        val abi = shell("getprop ro.product.cpu.abi").trim()
        check(abi in listOf("armeabi-v7a","arm64-v8a","x86_64"))
        val asset = File(checkNotNull(System.getProperty("airc.runtimeAssets")),"airc-runtime-$abi")
        val apk = File(checkNotNull(System.getProperty("airc.mediaFixtureApk")))
        check(asset.isFile && apk.isFile)
        val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val controller = VirtualGamepadController(scope,runtime)
        var created=false;var copied=false;var installed=false;var port:Int?=null
        suspend fun events() = shell("run-as $pkg cat files/gamepad-events.jsonl").lineSequence()
            .filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.toList()
        fun JsonObject.int(key:String)=getValue(key).jsonPrimitive.int
        suspend fun awaitEvents(predicate:(List<JsonObject>)->Boolean):List<JsonObject> = withTimeout(7000) {
            var value=events()
            while(!predicate(value)){delay(50);value=events()}
            value
        }
        try {
            root("mkdir -m 700 $directory");created=true
            // adb push runs as shell, so keep the owned directory accessible until upload.
            root("chown 2000:2000 $directory")
            adb.command("-s",serial!!,"push",asset.absolutePath,binary);copied=true
            root("chmod 700 $binary")
            val started=Json.parseToJsonElement(root("$binary service start --state-dir $directory/state --port 0 --no-mdns")).jsonObject
            assertTrue(started.getValue("ready").jsonPrimitive.boolean)
            port=adb.forward(serial,started.getValue("port").jsonPrimitive.int)
            assertEquals(0,runtime.connect("127.0.0.1",port!!).uid)
            adb.command("-s",serial,"install",apk.absolutePath);installed=true
            shell("am start -W -n $pkg/.GamepadActivity")
            withTimeout(5000){while(runCatching{shell("run-as $pkg test -f files/gamepad-ready")}.isFailure)delay(50)}
            assertTrue(controller.start(true))
            val active=withTimeout(7000){controller.state.first{!it.starting}}
            assertTrue(active.running,active.error);assertNull(active.error)
            val name=checkNotNull(active.deviceName)
            delay(700) // Allow InputReader and InputDispatcher to register the new device.
            val input=VirtualGamepadInput(listOf("a","r1"),.5,-.25,.75,-1.0,1,-1)
            assertTrue(controller.replace(input))
            val received=awaitEvents{ list -> list.any{it["deviceName"]?.jsonPrimitive?.content==name&&it["kind"]?.jsonPrimitive?.content=="motion"&&it["z"]!!.jsonPrimitive.double>.7} }
            val motions=received.filter{it["kind"]!!.jsonPrimitive.content=="motion"&&it["deviceName"]!!.jsonPrimitive.content==name}
            assertTrue(motions.any { e -> listOf("x" to .5,"y" to -.25,"z" to .75,"rz" to -1.0,"hatX" to 1.0,"hatY" to -1.0).all{(key,value)->kotlin.math.abs(e[key]!!.jsonPrimitive.double-value)<.02} },motions.toString())
            assertTrue(motions.all{it.int("deviceId")>0 && it.int("source") and 16777232==16777232})
            val sequence=controller.state.value.sequence
            delay(1200)
            assertTrue(controller.state.value.sequence>=sequence+3,"No sustained heartbeat")
            assertTrue(controller.replace(VirtualGamepadInput()))
            val released=awaitEvents{list->listOf(96,103).all{code->list.any{it["kind"]!!.jsonPrimitive.content=="key"&&it.int("code")==code&&it.int("action")==1}}}
            for(code in listOf(96,103)) {
                val keys=released.filter{it["kind"]!!.jsonPrimitive.content=="key"&&it.int("code")==code&&it.int("repeat")==0}
                assertEquals(listOf(0,1),keys.map{it.int("action")})
                assertTrue(keys.all{it.int("deviceId")>0&&it["deviceName"]!!.jsonPrimitive.content==name&&it.int("source") and 1025==1025})
                assertTrue(keys[1]["eventTime"]!!.jsonPrimitive.long-keys[0]["eventTime"]!!.jsonPrimitive.long>=1200)
            }
            val movedAt=motions.maxOf{it["eventTime"]!!.jsonPrimitive.long}
            awaitEvents { list -> list.any { e ->
                e["kind"]!!.jsonPrimitive.content=="motion" && e["deviceName"]!!.jsonPrimitive.content==name &&
                    e["eventTime"]!!.jsonPrimitive.long>movedAt &&
                    listOf("x","y","z","rz","hatX","hatY").all{k->kotlin.math.abs(e[k]!!.jsonPrimitive.double)<.02}
            } }
            assertTrue(controller.replace(VirtualGamepadInput(buttons=listOf("b"))))
            awaitEvents{list->list.any{it["kind"]!!.jsonPrimitive.content=="key"&&it.int("code")==97&&it.int("action")==0}}
            controller.stop()
            withTimeout(7000){controller.state.first{!it.stopping}}
            assertEquals(VirtualGamepadInput(),controller.state.value.input)
            awaitEvents{list->list.any{it["kind"]!!.jsonPrimitive.content=="key"&&it.int("code")==97&&it.int("action")==1}}
            withTimeout(5000){while(root("cat /proc/bus/input/devices").contains(name))delay(50)}
        } finally {
            controller.stop();runtime.close();scope.cancel()
            try { if(copied)root("$binary service stop --state-dir $directory/state") }
            finally {
                try { port?.let{adb.removeForward(serial!!,it)} }
                finally {
                    try { if(created)root("rm -rf -- $directory") }
                    finally { if(installed)check(adb.command("-s",serial!!,"uninstall",pkg).contains("Success")) }
                }
            }
        }
    }
}
