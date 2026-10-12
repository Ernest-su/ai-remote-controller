package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*
import java.io.File
import java.security.MessageDigest

class ApkInspectionTargetTest {
    @Test fun liveApkInspectionMatchesSdkCertificateAndDoesNotInstall(): Unit = runBlocking {
        val serial = System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for target APK inspection", !serial.isNullOrBlank())
        val apk = File(checkNotNull(System.getProperty("airc.mediaFixtureApk")))
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: error("Android SDK required for independent apksigner check")
        val verifier = File(sdk, "build-tools").listFiles().orEmpty().map { File(it, "apksigner") }.filter { it.isFile }.maxByOrNull { it.parentFile.name } ?: error("apksigner unavailable")
        val verified = runHostCommand(listOf(verifier.absolutePath, "verify", "--print-certs", apk.absolutePath))
        val certificate = Regex("certificate SHA-256 digest: ([a-f0-9]{64})").find(verified)?.groupValues?.get(1) ?: error("SDK signer fingerprint missing")
        val hash = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input -> val block = ByteArray(64 * 1024); while (true) { val count = input.read(block); if (count < 0) break; hash.update(block, 0, count) } }
        val digest = hash.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val adb = HostAdb(); val target = ShellRuntimeTarget(serial!!)
        val runtime = RuntimeConnection(); val transfer = RuntimeTransferClient(runtime)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = ApkInspectionController(scope, runtime)
        val name = "airc-apk-info-${java.util.UUID.randomUUID()}.apk"
        try {
            target.prepare()
            val before = adb.command("-s", serial, "shell", "pm list packages ernest.airc.mediafixture")
            val status = runtime.connect("127.0.0.1", checkNotNull(target.port)); assertTrue("apps.apk_info" in status.tools)
            transfer.upload(name, apk.length(), digest, apk::inputStream)
            controller.inspect("inbox", name, true)
            val done = withTimeout(30000) { controller.state.first { !it.busy } }
            assertNull(done.error); val info = checkNotNull(done.info)
            assertEquals("ernest.airc.mediafixture", info.packageName)
            assertEquals(1L, info.versionCode); assertEquals("fixture", info.versionName)
            assertEquals(apk.length(), info.sizeBytes); assertEquals(digest, info.sha256)
            assertFalse(info.signatureVerified)
            assertTrue(info.signatures.any { it.scheme == "v2" && certificate in it.certificateSha256 })
            assertEquals(before, adb.command("-s", serial, "shell", "pm list packages ernest.airc.mediafixture"))
            assertFails { runtime.request("tool", "apps.apk_info", buildJsonObject { put("root", "inbox"); put("path", "../$name") }) }
        } finally {
            controller.clear(); scope.cancel(); transfer.close(); runtime.close()
            target.close()
        }
    }
}
