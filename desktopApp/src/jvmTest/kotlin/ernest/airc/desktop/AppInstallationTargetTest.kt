package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class AppInstallationTargetTest {
    private val pkg = "ernest.airc.mediafixture"
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun liveInstallationRejectsBeforeConfirmationUpdatesAndPreservesDataOnBadSignature(): Unit = runBlocking {
        val serial = System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned APK installation validation", !serial.isNullOrBlank())
        val adb = HostAdb()
        suspend fun exists() = adb.command("-s", serial!!, "shell", "pm list packages --user all $pkg").lines().any { it.trim() == "package:$pkg" }
        check(!exists()) { "Refusing to alter a pre-existing fixture package in any Android user" }
        val apk = File(checkNotNull(System.getProperty("airc.mediaFixtureApk")))
        val updateApk = File(checkNotNull(System.getProperty("airc.mediaUpdateFixtureApk")))
        assertTrue(apk.isFile); assertTrue(updateApk.isFile)
        val target = ShellRuntimeTarget(serial!!); val runtime = RuntimeConnection(); val transfer = RuntimeTransferClient(runtime)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AppInstallationController(scope, runtime)
        val inspection = ApkInspectionController(scope, runtime)
        val directory = Files.createTempDirectory("airc-install-fixture-").toFile()
        var ownsPackage = false
        suspend fun push(file: File): TargetApkInfo {
            val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
            file.inputStream().use { input -> while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
            val digest = hash.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            val name = "airc-install-${java.util.UUID.randomUUID()}.apk"
            transfer.upload(name, file.length(), digest, file::inputStream)
            inspection.inspect("inbox", name, true)
            val state = withTimeout(30000) { inspection.state.first { !it.busy } }
            assertNull(state.error); return checkNotNull(state.info)
        }
        suspend fun prepare(info: TargetApkInfo, update: Boolean): AppInstallProposal {
            controller.prepare(info, update, true)
            val state = withTimeout(30000) { controller.state.first { it.proposal != null || !it.busy } }
            assertNull(state.error); return checkNotNull(state.proposal)
        }
        suspend fun finish() = withTimeout(45000) { controller.state.first { !it.busy } }
        suspend fun version() = json.decodeFromString<TargetAppInfo>(runtime.request("tool", "apps.info", buildJsonObject { put("package", pkg) })).versionCode
        try {
            target.prepare()
            val status = runtime.connect("127.0.0.1", checkNotNull(target.port)); assertTrue("apps.install" in status.tools)
            val first = push(apk); assertEquals(1L, first.versionCode)
            var preparedTask = json.decodeFromString<TransferProgress>(runtime.request("tool", "apps.install", buildJsonObject { put("root", first.root); put("path", first.path); put("sha256", first.sha256) }))
            withTimeout(30000) { while(preparedTask.phase !in listOf("completed","failed","cancelled")) { delay(200); preparedTask=json.decodeFromString(runtime.request("tool","task.progress",buildJsonObject{put("id",preparedTask.id)})) } }
            assertEquals("completed",preparedTask.phase)
            val cancelledId = checkNotNull(preparedTask.result).jsonObject["id"]!!.jsonPrimitive.content
            val cancelled = json.decodeFromString<TransferProgress>(runtime.request("tool","task.cancel",buildJsonObject{put("id",preparedTask.id)}))
            assertEquals("cancelled",cancelled.phase)
            assertFails {runtime.request("approve_install",arguments=buildJsonObject{put("id",cancelledId)})}
            assertFalse(exists())
            val rejected = prepare(first, false); assertFalse(exists())
            controller.confirm(false); assertNull(finish().error); assertFalse(exists())
            assertFails { runtime.request("approve_install", arguments = buildJsonObject { put("id", rejected.id) }) }
            prepare(first, false); ownsPackage = true
            controller.confirm(true); val installed = finish(); assertNull(installed.error); assertEquals("completed", installed.phase); assertEquals(1L, version())
            adb.command("-s", serial, "shell", "run-as $pkg sh -c 'mkdir -p files; printf fixture-data > files/airc-install-owned'")
            val second = push(updateApk); assertEquals(2L, second.versionCode)
            controller.prepare(second, false, true); assertNotNull(finish().error); assertEquals(1L, version())
            val replacement = prepare(second, true); assertTrue(replacement.codeMayAffectAllUsers); assertEquals(1L, replacement.currentApp?.versionCode)
            controller.confirm(true); assertNull(finish().error); assertEquals(2L, version())
            assertEquals("fixture-data", adb.command("-s", serial, "shell", "run-as $pkg cat files/airc-install-owned"))
            val bad = File(directory, "invalid-signature.apk")
            val bytes = updateApk.readBytes(); val needle = "classes.dex".toByteArray()
            val nameAt = (30 until bytes.size - needle.size).firstOrNull { offset ->
                needle.indices.all { bytes[offset + it] == needle[it] } && bytes[offset - 30] == 0x50.toByte() && bytes[offset - 29] == 0x4b.toByte() && bytes[offset - 28] == 3.toByte() && bytes[offset - 27] == 4.toByte()
            } ?: error("Fixture DEX local header missing")
            val header = nameAt - 30; val extra = (bytes[header + 28].toInt() and 255) or ((bytes[header + 29].toInt() and 255) shl 8)
            val dataAt = nameAt + needle.size + extra
            bytes[dataAt + 1] = (bytes[dataAt + 1].toInt() xor 1).toByte(); bad.writeBytes(bytes)
            val invalid = push(bad); prepare(invalid, true); controller.confirm(true)
            val failed = finish(); assertNotNull(failed.error); assertEquals("failed", failed.phase); assertEquals(2L, version())
            assertEquals("fixture-data", adb.command("-s", serial, "shell", "run-as $pkg cat files/airc-install-owned"))
        } finally {
            controller.clear(); inspection.clear(); scope.cancel(); transfer.close(); runtime.close()
            try {
                if (ownsPackage && exists()) check(adb.command("-s", serial, "uninstall", pkg).contains("Success"))
            } finally { try{target.close()}finally{directory.deleteRecursively()} }
        }
    }
}
