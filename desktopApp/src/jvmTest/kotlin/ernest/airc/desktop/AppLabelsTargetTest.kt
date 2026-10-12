package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*
import java.io.File

class AppLabelsTargetTest {
    @Test fun livePackageLabelsResolveResourceNamesAndMatchCurrentUserInventory(): Unit = runBlocking {
        val serial = System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned application label fixture", !serial.isNullOrBlank())
        val pkg = "ernest.airc.mediafixture"; val adb = HostAdb()
        check(adb.command("-s",serial!!,"shell","pm list packages --user all $pkg").lines().none { it.trim()=="package:$pkg" }) { "Refusing to replace an existing label fixture" }
        val apk = File(checkNotNull(System.getProperty("airc.mediaFixtureApk")))
        val target = ShellRuntimeTarget(serial); val runtime = RuntimeConnection(); var installed = false
        try {
            target.prepare()
            assertTrue(adb.command("-s",serial,"install",apk.absolutePath).contains("Success")); installed = true
            runtime.connect("127.0.0.1",checkNotNull(target.port))
            val output = runtime.request("tool","apps.list")
            val metadata = Json.parseToJsonElement(output).jsonObject
            assertEquals("package_manager",metadata["source"]?.jsonPrimitive?.content)
            assertEquals(true,metadata["labelsAvailable"]?.jsonPrimitive?.boolean)
            val apps = decodeInstalledApps(output)
            val own = apps.single { it.packageName==pkg }
            assertEquals("AIRC media test fixture",own.label); assertEquals(false,own.system)
            assertTrue(filterInstalledApps(apps,"media test").any { it.packageName==pkg })
            val expected = adb.command("-s",serial,"shell","pm list packages").lineSequence().filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.toSet()
            assertEquals(expected,apps.map { it.packageName }.toSet())
            assertTrue(apps.any { it.system==true && !it.label.isNullOrBlank() && it.label!=it.packageName })
        } finally {
            runtime.close()
            try { if(installed) check(adb.command("-s",serial,"uninstall",pkg).contains("Success")) }
            finally { target.close() }
        }
    }
}
