package ernest.airc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Executes the actual shell transaction against a real native background service. */
class RuntimeDeploymentTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    private fun hostBinary() = File(requireNotNull(System.getProperty("airc.hostRuntime"))).also { check(it.isFile) }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun command(vararg args: String): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        check(process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); "command timed out" }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { output }
        return output
    }
    /** Inspect only this test's process group; never dump unrelated process arguments. */
    private fun groupSnapshot(group: Long): String = File("/proc").listFiles().orEmpty()
        .filter { it.name.toLongOrNull() != null }
        .mapNotNull { directory -> runCatching {
            val stat = File(directory,"stat").readText()
            val fields = stat.substringAfterLast(')').trim().split(Regex("\\s+"))
            if(fields[2].toLong()!=group) return@runCatching null
            val signals = File(directory,"status").readLines().filter { it.startsWith("Sig") }.joinToString("; ")
            val wait = runCatching { File(directory,"wchan").readText().trim() }.getOrDefault("unavailable")
            "pid=${directory.name} stat=$stat wait=$wait $signals"
        }.getOrNull() }.joinToString("\n")
    private fun control(binary: File, directory: File, action: String, port: Int) =
        command(binary.path, "service", action, "--state-dir", File(directory, "state").path, "--port", port.toString())
    private fun ready(binary: File, directory: File, port: Int): String {
        val status = control(binary, directory, "status", port)
        assertTrue(Json.parseToJsonElement(status).jsonObject.getValue("ready").jsonPrimitive.boolean)
        return status
    }
    private fun candidate(directory: File, script: String): File = File(directory, "airc-runtime.next").apply {
        // Keep the real preflight while injecting a failure specifically at startup.
        writeText(script.replaceFirst("#!/bin/sh\n", "#!/bin/sh\nif [ \"\${2}\" = check-port ]; then exec ${hostBinary().path} \"\${@}\"; fi\n"))
        check(setExecutable(true))
    }
    private fun runTransaction(directory: File, port: Int, privilege: RuntimePrivilege = RuntimePrivilege(RuntimePrivilege.Mode.SHELL, false), fixtureTools: File? = null): Pair<Int, String> {
        val script = File(directory, "deploy.sh").apply { writeText(RuntimeDeployment.script(directory.path, port, privilege)) }
        val builder = ProcessBuilder("sh", "-c", privilege.command("sh ${RuntimePrivilege.quote(script.path)}")).redirectErrorStream(true)
        fixtureTools?.let { builder.environment()["PATH"] = "${it.path}:${System.getenv("PATH")}" }
        val process = builder.start()
        check(process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); "transaction timed out" }
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }
    /** Simulate Android su dispatch without acquiring host privileges or changing file owners. */
    private fun privilegeTools(oldRoot: Boolean = false, allowShell: Boolean = true): Pair<File, File> {
        val tools = temporary.newFolder()
        val trace = File(tools, "trace")
        fun tool(name: String, text: String) = File(tools, name).apply { writeText(text); check(setExecutable(true)) }
        tool("chown", "#!/bin/sh\necho ownership >> ${trace.path}\n")
        tool("stat", if (oldRoot) "#!/bin/sh\necho 0\n" else "#!/bin/sh\nexec /usr/bin/stat \"${'$'}@\"\n")
        tool("su", """
            #!/bin/sh
            if [ "${'$'}1" = 2000 ]; then
                ${if (!allowShell) "exit 1" else ":"}
                if [ "${'$'}3" = 'id -u' ]; then echo 2000; exit 0; fi
                echo shell-start >> ${trace.path}
                exec sh -c "${'$'}3"
            fi
            echo root-management >> ${trace.path}
            exec sh -c "${'$'}2"
        """.trimIndent() + "\n")
        return tools to trace
    }
    @Test fun occupiedPortRejectsFirstInstallWithoutReplacingFilesOrStoppingListener() {
        val directory = temporary.newFolder()
        hostBinary().copyTo(File(directory, "airc-runtime.next"))
        val state = File(directory, "state").apply { mkdir() }
        val identity = File(state, "device-id").apply { writeText("preserved-identity") }
        ServerSocket(0).use { listener ->
            val result = runTransaction(directory, listener.localPort)
            assertNotEquals(0, result.first)
            assertTrue(result.second, result.second.contains("airc-preflight-failed"))
            assertTrue(result.second, result.second.contains(listener.localPort.toString()))
            assertFalse(result.second.contains("airc-rolled-back"))
            assertFalse(File(directory, "airc-runtime").exists())
            assertEquals("preserved-identity", identity.readText())
            assertFalse(listener.isClosed)
        }
    }
    @Test fun occupiedNewPortLeavesExistingServiceAndBinaryUntouched() {
        val directory = temporary.newFolder()
        val binary = hostBinary().copyTo(File(directory, "airc-runtime")).apply { setExecutable(true) }
        val port = freePort()
        try {
            control(binary, directory, "start", port)
            val before = ready(binary, directory, port)
            val original = hash(binary)
            hostBinary().copyTo(File(directory, "airc-runtime.next"))
            ServerSocket(0).use { listener ->
                val result = runTransaction(directory, listener.localPort)
                assertNotEquals(0, result.first)
                assertTrue(result.second, result.second.contains("airc-preflight-failed"))
                assertEquals(before, ready(binary, directory, port))
                assertEquals(original, hash(binary))
                assertFalse(File(directory, "airc-runtime.previous").exists())
            }
        } finally { control(binary, directory, "stop", port) }
    }
    @Test fun deletedBinaryAndStateDoNotHideOrphanedListener() {
        val directory = temporary.newFolder()
        val binary = hostBinary().copyTo(File(directory, "airc-runtime")).apply { setExecutable(true) }
        val port = freePort()
        var orphan: ProcessHandle? = null
        try {
            val started = Json.parseToJsonElement(control(binary, directory, "start", port)).jsonObject
            orphan = ProcessHandle.of(started.getValue("pid").jsonPrimitive.content.toLong()).get()
            assertTrue(binary.delete())
            assertTrue(File(directory, "state").deleteRecursively())
            hostBinary().copyTo(File(directory, "airc-runtime.next"))
            val result = runTransaction(directory, port)
            assertNotEquals(0, result.first)
            assertTrue(result.second, result.second.contains("airc-preflight-failed"))
            assertTrue(orphan.isAlive)
            assertFalse(binary.exists())
            assertFalse(File(directory, "state/service.json").exists())
        } finally {
            orphan?.let { process ->
                process.destroy()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (process.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }
    @Test fun rootAdbdFirstInstallDoesNotRequireSuForShell() {
        val directory = temporary.newFolder()
        val binary = File(directory, "airc-runtime")
        hostBinary().copyTo(File(directory, "airc-runtime.next"))
        val (tools, trace) = privilegeTools(allowShell = false)
        val port = freePort()
        try {
            val result = runTransaction(directory, port, RuntimePrivilege(RuntimePrivilege.Mode.ROOT_ADBD, true), tools)
            assertEquals(result.second, 0, result.first)
            assertTrue(File(directory, ".root-mode").exists())
            assertFalse(trace.readText().contains("shell-start"))
            ready(binary, directory, port)
        } finally { if (binary.exists()) control(binary, directory, "stop", port) }
    }
    @Test fun failedRootUpgradeRestoresOldShellServiceAndMode() {
        val directory = temporary.newFolder()
        val binary = hostBinary().copyTo(File(directory, "airc-runtime")).apply { setExecutable(true) }
        val port = freePort()
        val (tools, trace) = privilegeTools()
        try {
            control(binary, directory, "start", port)
            val original = hash(binary)
            candidate(directory, "#!/bin/sh\nexit 1\n")
            val result = runTransaction(directory, freePort(), RuntimePrivilege(RuntimePrivilege.Mode.SU, true), tools)
            assertNotEquals(0, result.first)
            assertTrue(result.second, result.second.contains("airc-rolled-back"))
            assertEquals(original, hash(binary))
            assertTrue(trace.readText().contains("shell-start"))
            assertTrue(trace.readText().contains("root-management"))
            assertFalse(File(directory, ".root-mode").exists())
            ready(binary, directory, port)
        } finally { control(binary, directory, "stop", port) }
    }
    @Test fun disablingRootStartsShellAndRemovesModeMarker() {
        val directory = temporary.newFolder()
        val binary = hostBinary().copyTo(File(directory, "airc-runtime")).apply { setExecutable(true) }
        val port = freePort()
        val (tools, trace) = privilegeTools(oldRoot = true)
        try {
            control(binary, directory, "start", port)
            File(directory, ".root-mode").writeText("")
            hostBinary().copyTo(File(directory, "airc-runtime.next"))
            val result = runTransaction(directory, port, RuntimePrivilege(RuntimePrivilege.Mode.SU, false), tools)
            assertEquals(result.second, 0, result.first)
            assertTrue(trace.readText().contains("shell-start"))
            assertFalse(File(directory, ".root-mode").exists())
            ready(binary, directory, port)
        } finally { control(binary, directory, "stop", port) }
    }
    @Test fun failedShellDowngradeRestoresRootModeAndOriginalPort() {
        val directory = temporary.newFolder()
        val binary = hostBinary().copyTo(File(directory, "airc-runtime")).apply { setExecutable(true) }
        val port = freePort()
        val (tools, _) = privilegeTools(oldRoot = true)
        try {
            control(binary, directory, "start", port)
            File(directory, ".root-mode").writeText("")
            val original = hash(binary)
            candidate(directory, "#!/bin/sh\nexit 1\n")
            val result = runTransaction(directory, freePort(), RuntimePrivilege(RuntimePrivilege.Mode.SU, false), tools)
            assertNotEquals(0, result.first)
            assertTrue(result.second, result.second.contains("airc-rolled-back"))
            assertEquals(original, hash(binary))
            assertTrue(File(directory, ".root-mode").exists())
            ready(binary, directory, port)
        } finally { control(binary, directory, "stop", port) }
    }
    @Test fun unsupportedShellDemotionLeavesExistingRootServiceRunning() {
        val directory = temporary.newFolder()
        val binary = hostBinary().copyTo(File(directory, "airc-runtime")).apply { setExecutable(true) }
        val port = freePort()
        val (tools, _) = privilegeTools(oldRoot = true, allowShell = false)
        try {
            control(binary, directory, "start", port)
            val before = ready(binary, directory, port)
            File(directory, ".root-mode").writeText("")
            hostBinary().copyTo(File(directory, "airc-runtime.next"))
            val result = runTransaction(directory, port, RuntimePrivilege(RuntimePrivilege.Mode.SU, false), tools)
            assertNotEquals(0, result.first)
            assertEquals(before, ready(binary, directory, port))
            assertTrue(File(directory, ".root-mode").exists())
        } finally { control(binary, directory, "stop", port) }
    }
    @Test fun failedNewStartupRestoresOriginalBytesPortAndDeviceId() {
        val directory = temporary.newFolder()
        val binary = hostBinary().copyTo(File(directory, "airc-runtime")).apply { setExecutable(true) }
        val port = freePort()
        try {
            control(binary, directory, "start", port)
            val before = Json.parseToJsonElement(ready(binary, directory, port)).jsonObject
            val original = hash(binary)
            candidate(directory, "#!/bin/sh\necho startup-failed\nexit 1\n")
            val result = runTransaction(directory, freePort())
            assertNotEquals(0, result.first)
            assertTrue(result.second, result.second.contains("airc-rolled-back"))
            assertEquals(original, hash(binary))
            val after = Json.parseToJsonElement(ready(binary, directory, port)).jsonObject
            assertEquals(before["port"], after["port"])
            assertEquals(before["deviceId"], after["deviceId"])
            assertFalse(File(directory, "airc-runtime.next").exists())
            assertFalse(File(directory, "airc-runtime.previous").exists())
        } finally { control(binary, directory, "stop", port) }
    }
    @Test fun successfulReplacementStartsAndRemovesBackup() {
        val directory = temporary.newFolder()
        val binary = hostBinary().copyTo(File(directory, "airc-runtime")).apply { setExecutable(true) }
        val port = freePort()
        try {
            control(binary, directory, "start", port)
            val before = Json.parseToJsonElement(ready(binary, directory, port)).jsonObject
            val staged = hostBinary().copyTo(File(directory, "airc-runtime.next"))
            staged.appendText("\nupdated-fixture\n")
            val expected = hash(staged)
            val result = runTransaction(directory, port)
            assertEquals(result.second, 0, result.first)
            assertTrue(result.second.contains("airc-committed"))
            assertEquals(expected, hash(binary))
            val after = Json.parseToJsonElement(ready(binary, directory, port)).jsonObject
            assertEquals(before["deviceId"], after["deviceId"])
            assertNotEquals(before["pid"], after["pid"])
            assertFalse(File(directory, "airc-runtime.previous").exists())
        } finally { control(binary, directory, "stop", port) }
    }
    @Test fun failedFirstInstallationDoesNotLeaveABrokenInstalledBinary() {
        val directory = temporary.newFolder()
        candidate(directory, "#!/bin/sh\nexit 1\n")
        val result = runTransaction(directory, freePort())
        assertNotEquals(0, result.first)
        assertTrue(result.second.contains("airc-rolled-back"))
        assertFalse(File(directory, "airc-runtime").exists())
        assertFalse(File(directory, "airc-runtime.next").exists())
    }
    @Test fun hangupDuringCandidateStartupRestoresTheOwnedOldService() {
        val directory = temporary.newFolder()
        val binary = hostBinary().copyTo(File(directory, "airc-runtime")).apply { setExecutable(true) }
        val port = freePort()
        var process: Process? = null
        var failure: Throwable? = null
        try {
            control(binary, directory, "start", port)
            val original = hash(binary)
            val marker = File(directory, "startup-entered")
            // Publish readiness only after installing the signal disposition in the
            // final process. A shell's external touch/exec sequence has a fork window
            // in which group HUP can leave a child holding command-substitution pipes.
            candidate(directory, "#!/bin/sh\nif [ \"\${2}\" = start ]; then exec python3 -c 'import signal,time,os; signal.signal(signal.SIGHUP, signal.SIG_DFL); open(\"${marker.path}\", \"w\").write(str(os.getpid())); time.sleep(30)'; fi\nexit 1\n")
            val script = File(directory, "deploy.sh").apply { writeText(RuntimeDeployment.script(directory.path, port)) }
            val log = File(directory, "transaction.log")
            val group = File(directory, "process-group")
            // JVM launches can inherit ignored HUP; a shell cannot trap an inherited ignored signal.
            process = ProcessBuilder("env", "--default-signal=HUP", "setsid", "sh", "-c", "echo ${'$'}${'$'} > ${group.path}; exec sh ${script.path}")
                .redirectErrorStream(true).redirectOutput(log).start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (marker.length() == 0L && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue("candidate never entered startup", marker.length() > 0L)
            val groupId = group.readText().trim().toLong()
            val caught = File("/proc/$groupId/status").readLines().single { it.startsWith("SigCgt:") }.substringAfter(':').trim().toULong(16)
            assertTrue("fixture shell did not install HUP trap", caught and 1uL != 0uL)
            val candidatePid = marker.readText().trim().toLong()
            val candidateStat = File("/proc/$candidatePid/stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))
            assertEquals("candidate escaped transaction process group", groupId, candidateStat[2].toLong())
            for (pid in listOf(groupId, candidatePid)) {
                val status = File("/proc/$pid/status").readLines()
                val blocked = status.single { it.startsWith("SigBlk:") }.substringAfter(':').trim().toULong(16)
                val ignored = status.single { it.startsWith("SigIgn:") }.substringAfter(':').trim().toULong(16)
                assertEquals("fixture blocks HUP: pid $pid", 0uL, blocked and 1uL)
                assertEquals("fixture ignores HUP: pid $pid", 0uL, ignored and 1uL)
            }
            command("kill", "-HUP", "--", "-$groupId")
            // Rollback may stop an owned process (5 s) and restart it (12 s).
            // Allow those production deadlines plus scheduling overhead.
            val exited = process.waitFor(20, TimeUnit.SECONDS)
            assertTrue("hangup rollback did not finish; log: ${log.readText()}; group: ${if(exited) "exited" else groupSnapshot(groupId)}", exited)
            assertTrue(log.readText(), log.readText().contains("airc-rolled-back"))
            assertEquals(original, hash(binary))
            ready(binary, directory, port)
        } catch(error: Throwable) {
            failure = error
            throw error
        } finally {
            process?.let { if (it.isAlive) { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) } }
            try { control(binary, directory, "stop", port) }
            catch(cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
        }
    }
}
