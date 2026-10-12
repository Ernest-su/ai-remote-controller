package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*
import java.io.File

class AppUninstallTargetTest {
    private val fixturePackage = "ernest.airc.mediafixture"
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun liveUninstallRequiresConfirmationAndOnlyRemovesOwnFixture(): Unit = runBlocking {
        val serial = System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for controlled application uninstall", !serial.isNullOrBlank())
        val adb = HostAdb()
        suspend fun installed() = adb.command("-s", serial!!, "shell", "pm list packages --user all $fixturePackage")
            .lines().any { it.trim() == "package:$fixturePackage" }
        check(!installed()) { "Refusing to replace an existing fixture application in any Android user" }
        val apk = File(checkNotNull(System.getProperty("airc.mediaFixtureApk")))
        assertTrue(apk.isFile)
        val target = ShellRuntimeTarget(serial!!)
        val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AppManagementController(scope, runtime)
        var owned = false
        try {
            target.prepare()
            assertTrue(adb.command("-s", serial, "install", apk.absolutePath).contains("Success"))
            owned = true
            val status = runtime.connect("127.0.0.1", checkNotNull(target.port))
            assertTrue("apps.uninstall" in status.tools)
            val info = json.decodeFromString<TargetAppInfo>(runtime.request("tool", "apps.info", buildJsonObject { put("package", fixturePackage) }))
            assertEquals(fixturePackage, info.packageName)
            assertFalse(info.system)
            assertTrue(info.appId >= 10000)
            assertTrue(info.versionCode > 0)
            assertFails { runtime.request("tool", "apps.uninstall", buildJsonObject { put("package", "com.konka.multimedia") }) }
            controller.uninstall(fixturePackage, true)
            withTimeout(5000) { controller.state.first { it.proposal != null } }
            assertTrue(installed(), "Preparing a proposal must preserve the package")
            controller.confirm(false)
            withTimeout(5000) { controller.state.first { !it.busy } }
            assertNull(controller.state.value.error)
            assertTrue(installed(), "Rejecting a proposal must preserve the package")
            controller.uninstall(fixturePackage, true)
            val proposal = withTimeout(5000) { controller.state.first { it.proposal != null } }.proposal!!
            assertEquals(info.userId, proposal.app.userId)
            controller.confirm(true)
            val done = withTimeout(45000) { controller.state.first { !it.busy } }
            assertNull(done.error)
            assertEquals("completed", done.phase)
            assertFalse(installed())
            owned = false
            assertFails { runtime.request("approve_app", arguments = buildJsonObject { put("id", proposal.id) }) }
        } finally {
            controller.clear(); scope.cancel(); runtime.close()
            try { if (owned) check(adb.command("-s", serial, "uninstall", fixturePackage).contains("Success")) }
            finally { target.close() }
        }
    }
}
