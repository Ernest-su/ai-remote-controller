package ernest.airc.desktop

import ernest.airc.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class DirectoryCreationTargetTest {
    @Test fun createsDirectoryRefreshesAndPreservesExistingEntry():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned directory fixture",!serial.isNullOrBlank())
        val adb=HostAdb();val directory="/data/local/tmp/airc-mkdir-${java.util.UUID.randomUUID()}"
        val binary="$directory/airc-runtime";var owned=false;var copied=false;var port:Int?=null;var relay:TcpFaultRelay?=null
        val runtime=RuntimeConnection();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val browser=FileBrowserController(scope,runtime)
        suspend fun shell(command:String)=adb.command("-s",serial!!,"shell",command)
        try {
            val abi=shell("getprop ro.product.cpu.abi").trim()
            check(abi in listOf("armeabi-v7a","arm64-v8a","x86_64"))
            val asset=File(checkNotNull(System.getProperty("airc.runtimeAssets")),"airc-runtime-$abi");check(asset.isFile)
            shell("mkdir -m 700 $directory");owned=true
            adb.command("-s",serial!!,"push",asset.absolutePath,binary);copied=true
            shell("chmod 700 $binary")
            val service=Json.parseToJsonElement(shell("$binary service start --state-dir $directory/state --port 0 --no-mdns")).jsonObject
            assertTrue(service["ready"]!!.jsonPrimitive.boolean)
            port=adb.forward(serial,service["port"]!!.jsonPrimitive.int)
            relay=TcpFaultRelay(port!!);val status=runtime.connect("127.0.0.1",relay.port);assertTrue("files.mkdir" in status.tools)
            browser.selectRoot("inbox");browser.createDirectory("new",true)
            val created=withTimeout(5000){browser.state.first{!it.loading&&(it.loaded||it.error!=null)}}
            assertNull(created.error);assertEquals("directory",created.entries.single{it.name=="new"}.kind)
            browser.inspect(created.entries.single{it.name=="new"},true)
            val inspected=withTimeout(5000){browser.state.first{!it.loading}}
            assertNull(inspected.error);assertEquals("new",inspected.selected?.path)
            assertEquals("directory",inspected.selected?.kind)
            shell("printf owned > $directory/state/files/new/marker")
            browser.createDirectory("new",true)
            val duplicate=withTimeout(5000){browser.state.first{!it.loading}}
            assertNotNull(duplicate.error);assertEquals("owned",shell("cat $directory/state/files/new/marker"))
            val beforeGeneration=checkNotNull(runtime.session.value).generation
            relay.cut();withTimeout(5000){runtime.connected.first{!it}}
            assertNull(runtime.session.value)
            relay.restore();val resumed=runtime.connect("127.0.0.1",relay.port)
            assertEquals(status.deviceId,resumed.deviceId)
            assertTrue(checkNotNull(runtime.session.value).generation>beforeGeneration)
            browser.clear();browser.selectRoot("inbox");browser.refresh(true)
            val restored=withTimeout(5000){browser.state.first{!it.loading}}
            assertNull(restored.error);assertTrue(restored.entries.any{it.name=="new"&&it.kind=="directory"})
            assertEquals("owned",shell("cat $directory/state/files/new/marker"))
            assertFails{runtime.request("tool","files.mkdir",buildJsonObject{put("root","inbox");put("path","../outside")})}
            assertFails{runtime.request("tool","files.mkdir",buildJsonObject{put("root","inbox");put("path","missing/child")})}
        }finally{
            browser.clear();scope.cancel();runtime.close();relay?.close()
            try{if(copied)shell("$binary service stop --state-dir $directory/state")}
            finally{try{port?.let{adb.removeForward(serial!!,it)}}finally{if(owned)shell("rm -rf -- $directory")}}
        }
    }
}
