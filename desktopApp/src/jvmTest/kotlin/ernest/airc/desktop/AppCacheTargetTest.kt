package ernest.airc.desktop
import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.*

class AppCacheTargetTest {
    @Test fun shellCacheCapabilityFailsClosedWithoutTheInternalPermission():Unit=runCache(false)
    @Test fun rootCacheOnlyRequiresConfirmationAndPreservesOwnedAppData():Unit=runCache(true)
    private fun runCache(root:Boolean):Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial");assumeTrue("Set -PaircTargetSerial for owned cache fixture",!serial.isNullOrBlank())
        val adb=HostAdb();val pkg="ernest.airc.mediafixture"
        check(adb.command("-s",serial!!,"shell","pm list packages --user all $pkg").lines().none{it=="package:$pkg"}){"Refusing to replace existing fixture"}
        val apk=File(checkNotNull(System.getProperty("airc.mediaFixtureApk")))
        val target=ShellRuntimeTarget(serial,root)
        val runtime=RuntimeConnection();val controller=AppCacheController(this,runtime);var installed=false
        suspend fun own(command:String)=adb.command("-s",serial,"shell","run-as $pkg sh -c '$command'")
        try {
            adb.command("-s",serial,"install",apk.absolutePath);installed=true
            own("mkdir -p cache files shared_prefs databases; printf cache-marker > cache/owned; printf data-marker > files/owned; printf prefs-marker > shared_prefs/owned.xml; printf database-marker > databases/owned.db")
            target.prepare()
            val status=runtime.connect("127.0.0.1",checkNotNull(target.port))
            assertEquals(if(root)0 else 2000,status.uid)
            val capability=Json.parseToJsonElement(runtime.request("tool","apps.cache_capabilities")).jsonObject
            if(root)assertTrue(capability["supported"]!!.jsonPrimitive.boolean,"Root fixture must exercise actual cache cleanup")
            if(!capability["supported"]!!.jsonPrimitive.boolean) {
                assertFalse(capability["internalDeleteCacheGranted"]!!.jsonPrimitive.boolean)
                assertFails{runtime.request("tool","apps.clear_cache",buildJsonObject{put("package",pkg)})}
                assertEquals("cache-marker",own("cat cache/owned"));assertEquals("data-marker",own("cat files/owned"))
                return@runBlocking
            }
            val before=own("sha256sum files/owned shared_prefs/owned.xml databases/owned.db")
            controller.prepare(pkg,true);withTimeout(10000){controller.state.first{it.proposal!=null}}
            val rejectedId=controller.state.value.proposal!!.id;assertEquals("cache-marker",own("cat cache/owned"));controller.confirm(false)
            withTimeout(7000){controller.state.first{!it.busy}};assertEquals("cache-marker",own("cat cache/owned"))
            assertFails{runtime.request("approve_cache",arguments=buildJsonObject{put("id",rejectedId)})}
            controller.prepare(pkg,true);withTimeout(10000){controller.state.first{it.proposal!=null}};controller.confirm(true)
            val done=withTimeout(15000){controller.state.first{!it.busy}};assertNull(done.error)
            assertEquals("completed",done.phase)
            assertNull(done.proposal)
            assertTrue(checkNotNull(done.message).contains(pkg))
            own("test ! -e cache/owned");assertEquals(before,own("sha256sum files/owned shared_prefs/owned.xml databases/owned.db"))
            assertTrue(Json.parseToJsonElement(runtime.request("tool","apps.info",buildJsonObject{put("package",pkg)})).jsonObject["package"]!!.jsonPrimitive.content==pkg)
            assertFails{runtime.request("tool","apps.clear_cache",buildJsonObject{put("package","com.android.settings")})}
            assertFails{runtime.request("tool","apps.clear_cache",buildJsonObject{put("package",pkg);put("data",true)})}
        } finally {
            controller.clear();runtime.close()
            try{if(installed)check(adb.command("-s",serial,"uninstall",pkg).contains("Success"))}
            finally { target.close() }
        }
    }
}
