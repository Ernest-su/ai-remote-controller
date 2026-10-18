package ernest.airc.desktop

import ernest.airc.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** UUID service state and an owned fixture package only; no existing application is changed. */
class AppMetadataCacheTargetTest {
    @Test fun persistentMetadataAndIconsRevalidateUpdatesUninstallsAndLiveState(): Unit = runBlocking {
        val serial = System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned application cache fixture", !serial.isNullOrBlank())
        val pkg="ernest.airc.mediafixture"; val adb=HostAdb()
        suspend fun shell(command:String)=adb.command("-s",serial!!,"shell",command).trim()
        check(shell("pm list packages --user all $pkg").lines().none { it=="package:$pkg" }) { "Refusing to replace existing cache fixture" }
        val apk=File(checkNotNull(System.getProperty("airc.mediaFixtureApk")))
        val update=File(checkNotNull(System.getProperty("airc.mediaUpdateFixtureApk")))
        val target=ShellRuntimeTarget(serial!!);val runtime=RuntimeConnection()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val icons=AppIconController(scope,runtime)
        var installed=false
        val args=buildJsonObject { put("package",pkg) }
        suspend fun list():List<InstalledApp> {
            val text=runtime.request("tool","apps.list")
            assertEquals("package_manager",Json.parseToJsonElement(text).jsonObject["source"]?.jsonPrimitive?.content)
            return decodeInstalledApps(text)
        }
        suspend fun info()=Json.parseToJsonElement(runtime.request("tool","apps.info",args)).jsonObject
        suspend fun state()=Json.parseToJsonElement(runtime.request("tool","apps.control_info",args)).jsonObject.getValue("state").jsonObject
        try {
            target.prepare()
            assertTrue(adb.command("-s",serial,"install",apk.absolutePath).contains("Success"));installed=true
            runtime.connect("127.0.0.1",checkNotNull(target.port))
            val first=list();val own=first.single { it.packageName==pkg }
            assertEquals("AIRC media test fixture",own.label);assertNotNull(own.cacheKey)
            val initial=info();assertEquals(1,initial.getValue("versionCode").jsonPrimitive.int)
            val user=initial.getValue("userId").jsonPrimitive.int
            val directory=target.inboxPath.removeSuffix("/files")+"/platform/apps-cache-v1"
            val metadata="$directory/$user-$pkg.json";val png="$directory/$user-$pkg.png"
            icons.updateApps(first);val bytes=icons.load(pkg);assertTrue(bytes.size>8)
            val inode=shell("stat -c '%i' $png");val metaInode=shell("stat -c '%i' $metadata")
            val warm=list();icons.updateApps(warm);assertContentEquals(bytes,icons.load(pkg))
            val descriptor=Json.parseToJsonElement(runtime.request("tool","apps.icon",args)).jsonObject
            assertEquals(own.cacheKey,descriptor.getValue("cacheKey").jsonPrimitive.content)
            assertEquals(inode,shell("stat -c '%i' $png"));assertEquals(metaInode,shell("stat -c '%i' $metadata"))
            // The cache must survive a service process restart without rewriting these entries.
            runtime.disconnect();target.restart();runtime.connect("127.0.0.1",checkNotNull(target.port))
            val resumed=list();assertEquals(own.cacheKey,resumed.single { it.packageName==pkg }.cacheKey)
            runtime.request("tool","apps.icon",args)
            assertEquals(inode,shell("stat -c '%i' $png"));assertEquals(metaInode,shell("stat -c '%i' $metadata"))
            // Same-version replacement keeps firstInstallTime but must invalidate the cache.
            delay(1100)
            assertTrue(adb.command("-s",serial,"install","-r",apk.absolutePath).contains("Success"))
            val replaced=list();val replacement=replaced.single { it.packageName==pkg }
            assertNotEquals(own.cacheKey,replacement.cacheKey)
            assertEquals(initial.getValue("firstInstallTime"),info().getValue("firstInstallTime"))
            assertEquals(1,info().getValue("versionCode").jsonPrimitive.int)
            icons.updateApps(replaced);icons.load(pkg)
            assertNotEquals(inode,shell("stat -c '%i' $png"))
            assertTrue(adb.command("-s",serial,"install","-r",update.absolutePath).contains("Success"))
            val updated=list();val newer=updated.single { it.packageName==pkg }
            assertNotEquals(replacement.cacheKey,newer.cacheKey);assertEquals(2,info().getValue("versionCode").jsonPrimitive.int)
            icons.updateApps(updated);icons.load(pkg)
            // Corrupt icon files are never served from disk, even when version metadata matches.
            shell("printf corrupt > $png")
            icons.clear();icons.updateApps(updated);assertContentEquals(bytes,icons.load(pkg))
            shell("printf corrupt > $metadata")
            assertEquals(newer.cacheKey,list().single { it.packageName==pkg }.cacheKey)
            // Exercise resource-configuration invalidation and restore the original global setting.
            val night=shell("cmd uimode night").substringAfter("Night mode: ").trim()
            check(night in listOf("yes","no","auto","custom")) { "Unknown night-mode state: $night" }
            try {
                shell("cmd uimode night ${if(night=="yes") "no" else "yes"}");delay(500)
                assertNotEquals(newer.cacheKey,list().single { it.packageName==pkg }.cacheKey)
            } finally { shell("cmd uimode night $night");delay(500) }
            val restored=list();val restoredKey=restored.single { it.packageName==pkg }.cacheKey
            icons.updateApps(restored)
            // Enabled state and permissions remain live while the static identity stays unchanged.
            shell("pm disable-user --user $user $pkg")
            assertFalse(state().getValue("enabled").jsonPrimitive.boolean)
            assertEquals(restoredKey,list().single { it.packageName==pkg }.cacheKey)
            shell("pm enable --user $user $pkg");assertTrue(state().getValue("enabled").jsonPrimitive.boolean)
            val permission="android.permission.READ_EXTERNAL_STORAGE"
            shell("pm grant --user $user $pkg $permission")
            fun granted(value:JsonObject)=value.getValue("permissions").jsonArray.single { it.jsonObject.getValue("name").jsonPrimitive.content==permission }.jsonObject.getValue("granted").jsonPrimitive.boolean
            assertTrue(granted(state()))
            shell("pm revoke --user $user $pkg $permission");assertFalse(granted(state()))
            assertEquals(restoredKey,list().single { it.packageName==pkg }.cacheKey)
            assertTrue(adb.command("-s",serial,"uninstall",pkg).contains("Success"));installed=false
            val removed=list();icons.updateApps(removed)
            assertTrue(removed.none { it.packageName==pkg });assertNull(icons.cached(pkg))
            assertEquals("",shell("find $directory -name '$user-$pkg.*' -print"))
            assertFails { runtime.request("tool","apps.icon",args) }
        } finally {
            icons.clear();scope.cancel();runtime.close()
            try { if(installed) check(adb.command("-s",serial,"uninstall",pkg).contains("Success")) }
            finally { target.close() }
        }
    }
}
