package ernest.airc.desktop
import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class RuntimePrivilegeTargetTest {
    @Test fun realSuSyntaxStartsRootRestoresShellAndRollsBackFailedUpgrade():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial");assumeTrue("Set -PaircTargetSerial for isolated privilege transaction",!serial.isNullOrBlank())
        val deploymentScript=checkNotNull(System.getProperty("airc.deploymentScript"))
        val adb=HostAdb();val dir="/data/local/tmp/airc-privilege-test-${java.util.UUID.randomUUID()}";val binary="$dir/airc-runtime"
        suspend fun shell(command:String)=adb.command("-s",serial!!,"shell",command)
        val root=RuntimePrivilege.detect(true,dir,::shell);check(root.useRoot){"Target has no verified root manager"}
        val runtime=RuntimeConnection();var forwarded:Int?=null;var created=false
        try {
            shell("mkdir -m 700 $dir && cp /data/local/tmp/airc/airc-runtime $binary && chmod 700 $binary");created=true
            val started=Json.parseToJsonElement(shell("$binary service start --state-dir $dir/state --port 0")).jsonObject
            val port=started.getValue("port").jsonPrimitive.int
            forwarded=adb.forward(serial!!,port)
            val original=runtime.status("127.0.0.1",forwarded!!);assertEquals(2000,original.uid)
            shell("printf retained > $dir/state/owned-marker")
            suspend fun deploy(privilege:RuntimePrivilege):String=shell(privilege.command("sh -c ${RuntimePrivilege.quote(RuntimeDeployment.script(dir,port,privilege))}"))
            suspend fun stage() {shell(root.command("cp /data/local/tmp/airc/airc-runtime $binary.next && chown 2000:2000 $binary.next && chmod 700 $binary.next"))}
            stage();assertTrue(deploy(root).contains("airc-committed"))
            val elevated=runtime.status("127.0.0.1",forwarded!!);assertEquals(0,elevated.uid);assertEquals(original.deviceId,elevated.deviceId)
            assertEquals("retained",shell(root.command("cat $dir/state/owned-marker")))
            runtime.connect("127.0.0.1",forwarded!!)
            assertTrue(Json.parseToJsonElement(runtime.request("tool","apps.cache_capabilities")).jsonObject.getValue("supported").jsonPrimitive.boolean)
            runtime.disconnect()
            val cli=runHostCommand(listOf(deploymentScript,"--serial",serial!!,"--no-root","--android-dir",dir,"--port",port.toString()),timeoutSeconds=120)
            assertTrue(cli.contains("airc-committed"));assertEquals(2000,runtime.status("127.0.0.1",forwarded!!).uid)
            val cliRoot=runHostCommand(listOf(deploymentScript,"--serial",serial!!,"--root","--android-dir",dir,"--port",port.toString()),timeoutSeconds=120)
            assertTrue(cliRoot.contains("airc-committed"));assertEquals(0,runtime.status("127.0.0.1",forwarded!!).uid)
            val shellPrivilege=RuntimePrivilege.detect(false,dir,::shell);assertFalse(shellPrivilege.useRoot);assertTrue(shellPrivilege.manager!=RuntimePrivilege.Mode.SHELL)
            stage();assertTrue(deploy(shellPrivilege).contains("airc-committed"))
            val downgraded=runtime.status("127.0.0.1",forwarded!!);assertEquals(2000,downgraded.uid);assertEquals(original.deviceId,downgraded.deviceId)
            assertEquals("retained",shell("cat $dir/state/owned-marker"))
            shell("printf '#!/system/bin/sh\\nexit 1\\n' > $binary.next && chmod 700 $binary.next")
            val rollback=assertFails {deploy(root)}
            assertTrue(rollback.message.orEmpty().contains("airc-rolled-back"))
            val restored=runtime.status("127.0.0.1",forwarded!!);assertEquals(2000,restored.uid);assertEquals(original.deviceId,restored.deviceId)
            assertEquals("retained",shell("cat $dir/state/owned-marker"));shell("test ! -e $dir/.root-mode")
        } finally {
            runtime.close();forwarded?.let{adb.removeForward(serial!!,it)}
            if(created) {shell(root.command("$binary service stop --state-dir $dir/state"));shell(root.command("rm -rf -- $dir"))}
        }
    }
}
