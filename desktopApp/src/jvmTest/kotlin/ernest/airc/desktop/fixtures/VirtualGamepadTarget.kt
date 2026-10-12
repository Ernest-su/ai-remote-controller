package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File

/** Owns only its UUID service, forward and absent-before-test receiver package. */
internal class VirtualGamepadTarget(val serial: String) {
    private val adb=HostAdb()
    private val pkg="ernest.airc.mediafixture"
    private val directory="/data/local/tmp/airc-compose-gamepad-${java.util.UUID.randomUUID()}"
    private val binary="$directory/airc-runtime"
    private lateinit var privilege: RuntimePrivilege
    private var runtimePid:Int?=null
    private var created=false;private var copied=false;private var installed=false
    var port:Int?=null;private set
    var runtimePort:Int?=null;private set
    suspend fun shell(command:String)=adb.command("-s",serial,"shell",command)
    private suspend fun root(command:String)=shell(privilege.command(command))
    suspend fun prepare(activity:String=".GamepadActivity",preferRoot:Boolean=true) {
        require(activity in listOf(".GamepadActivity",".ColorActivity",".NoiseActivity",".TouchActivity"))
        check(shell("pm list packages --user all $pkg").lines().none{it=="package:$pkg"}) {"Refusing to replace existing fixture"}
        privilege=RuntimePrivilege.detect(preferRoot,directory,::shell);check(privilege.useRoot==preferRoot)
        val abi=shell("getprop ro.product.cpu.abi").trim();check(abi in listOf("armeabi-v7a","arm64-v8a","x86_64"))
        val asset=File(checkNotNull(System.getProperty("airc.runtimeAssets")),"airc-runtime-$abi")
        val apk=File(checkNotNull(System.getProperty("airc.mediaFixtureApk")));check(asset.isFile&&apk.isFile)
        root("mkdir -m 700 $directory");created=true
        if(privilege.useRoot)root("chown 2000:2000 $directory")
        adb.command("-s",serial,"push",asset.absolutePath,binary);copied=true
        root("chmod 700 $binary")
        val started=Json.parseToJsonElement(root("$binary service start --state-dir $directory/state --port 0 --no-mdns")).jsonObject
        check(started.getValue("ready").jsonPrimitive.boolean)
        runtimePid=started.getValue("pid").jsonPrimitive.int
        runtimePort=started.getValue("port").jsonPrimitive.int
        port=adb.forward(serial,runtimePort!!)
        adb.command("-s",serial,"install",apk.absolutePath);installed=true
        shell("am start -W -n $pkg/$activity")
        if(activity==".GamepadActivity") withTimeout(5000){while(runCatching{shell("run-as $pkg test -f files/gamepad-ready")}.isFailure)delay(50)}
        else if(activity==".TouchActivity") withTimeout(5000){while(runCatching{shell("run-as $pkg test -f files/touch-ready")}.isFailure)delay(50)}
        else delay(500)
    }
    suspend fun colorOrientation(orientation:String) {
        require(orientation in listOf("portrait","landscape"))
        shell("am start -W --activity-single-top -n $pkg/.ColorActivity --es orientation $orientation")
    }
    suspend fun encoderPid():Int? = root("ps -A -o PID,PPID,ARGS").lineSequence()
        .map{it.trim().split(Regex("\\s+"),limit=3)}
        .firstOrNull{it.size==3&&it[1].toIntOrNull()==runtimePid&&it[2].startsWith("screenrecord ")}
        ?.first()?.toInt()
    suspend fun encoderWait(pid:Int):String {
        check(encoderPid()==pid) { "Observer must target this service's encoder" }
        return root("cat /proc/$pid/wchan").trim()
    }
    suspend fun events()=shell("run-as $pkg cat files/gamepad-events.jsonl").lineSequence().filter{it.isNotBlank()}
        .map{Json.parseToJsonElement(it).jsonObject}.toList()
    suspend fun awaitEvent(predicate:(JsonObject)->Boolean):JsonObject=withTimeout(7000){
        var found=events().firstOrNull(predicate)
        while(found==null){delay(50);found=events().firstOrNull(predicate)}
        found
    }
    suspend fun awaitRemoved(name:String)=withTimeout(5000){while(root("cat /proc/bus/input/devices").contains(name))delay(50)}
    suspend fun close() {
        try { if(copied)root("$binary service stop --state-dir $directory/state") }
        finally {
            try {port?.let{adb.removeForward(serial,it)}}
            finally {try{if(created)root("rm -rf -- $directory")}
                finally{if(installed)check(adb.command("-s",serial,"uninstall",pkg).contains("Success"))}}
        }
    }
}
