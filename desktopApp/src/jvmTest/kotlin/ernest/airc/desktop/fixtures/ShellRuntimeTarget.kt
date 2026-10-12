package ernest.airc.desktop

import ernest.airc.*
import java.io.File
import kotlinx.serialization.json.*

/** Disposable binary service with verified shell/root identity; no main-service changes. */
internal class ShellRuntimeTarget(private val serial:String, private val root:Boolean=false, private val mdns:Boolean=false) {
    private val adb=HostAdb()
    private val directory="/data/local/tmp/airc-shell-test-${java.util.UUID.randomUUID()}"
    private val binary="$directory/airc-runtime"
    private var created=false;private var copied=false
    private var privilege:RuntimePrivilege?=null
    private suspend fun shell(command:String)=adb.command("-s",serial,"shell",command)
    private suspend fun managed(command:String)=shell(privilege?.command(command) ?: command)
    val inboxPath:String get()="$directory/state/files"
    var port:Int?=null;private set
    var runtimePort:Int?=null;private set
    suspend fun prepare() {
        privilege=RuntimePrivilege.detect(root,directory,::shell)
        check(privilege!!.useRoot==root) { "Requested test privilege unavailable" }
        val abi=adb.command("-s",serial,"shell","getprop ro.product.cpu.abi").trim()
        check(abi in listOf("armeabi-v7a","arm64-v8a","x86_64"))
        val asset=File(checkNotNull(System.getProperty("airc.runtimeAssets")),"airc-runtime-$abi");check(asset.isFile)
        managed("mkdir -m 700 $directory");created=true
        if(privilege!!.manager!=RuntimePrivilege.Mode.SHELL)managed("chown 2000:2000 $directory")
        adb.command("-s",serial,"push",asset.absolutePath,binary);copied=true
        managed("chmod 700 $binary")
        if(!root && privilege!!.manager==RuntimePrivilege.Mode.ROOT_ADBD)managed("chown 2000:2000 $binary")
        start()
    }
    private suspend fun start() {
        val service=Json.parseToJsonElement(shell(privilege!!.launch("$binary service start --state-dir $directory/state --port 0${if(mdns)""else" --no-mdns"}"))).jsonObject
        check(service["ready"]!!.jsonPrimitive.boolean)
        runtimePort=service["port"]!!.jsonPrimitive.int
        port=adb.forward(serial,runtimePort!!)
    }
    suspend fun restart() {
        check(created && copied && privilege!=null) { "Test service has not been prepared" }
        managed("$binary service stop --state-dir $directory/state")
        port?.let { adb.removeForward(serial,it) };port=null
        start()
    }
    suspend fun inboxEntries():String=managed("find $directory/state/files -mindepth 1 -maxdepth 1 -print")
    suspend fun close() {
        try{if(copied)managed("$binary service stop --state-dir $directory/state")}
        finally{try{port?.let{adb.removeForward(serial,it)}}finally{if(created)managed("rm -rf -- $directory")}}
    }
}
