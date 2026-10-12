package ernest.airc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class RuntimeDeploymentRunnerTest {
    private val directory = "/data/local/tmp/airc"
    private val modes = listOf(
        RuntimePrivilege(RuntimePrivilege.Mode.SHELL, false),
        RuntimePrivilege(RuntimePrivilege.Mode.ROOT_ADBD, true),
        RuntimePrivilege(RuntimePrivilege.Mode.SU, true),
        RuntimePrivilege(RuntimePrivilege.Mode.SU, false, RuntimePrivilege.SuStyle.UID_SHELL),
    )
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test fun legacyPacketLimitForEveryPrivilegeMode() = runBlocking {
        for (privilege in modes) {
            var uploaded = byteArrayOf()
            var path = ""
            val commands = mutableListOf<String>()
            val result = RuntimeDeploymentRunner.run(directory, 9876, privilege,
                push = { bytes, remote -> uploaded = bytes; path = remote },
                shell = { command ->
                    commands += command
                    check(("shell:$command\u0000").toByteArray().size <= 4096)
                    when (command) {
                        privilege.command("sha256sum '${path}'") -> "${hash(uploaded)}  $path\n"
                        privilege.command("sh '${path}'") -> "airc-committed\n"
                        privilege.command("rm -f '${path}'") -> ""
                        else -> error(command)
                    }
                })
            assertTrue(uploaded.size > 4096)
            assertEquals(RuntimeDeployment.script(directory, 9876, privilege), uploaded.toString(Charsets.UTF_8))
            assertEquals("airc-committed\n", result)
            assertEquals(3, commands.size)
        }
    }

    @Test fun cancelledUploadStillCleansRemoteScript() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var cleanup = false
        val job = launch {
            RuntimeDeploymentRunner.run(directory, 9876, modes.first(),
                push = { _, _ -> entered.complete(Unit); awaitCancellation() },
                shell = { command -> cleanup = command.startsWith("rm -f "); "" })
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(cleanup)
    }

    @Test fun failuresNeverSkipCleanup() = runBlocking {
        for (failure in listOf("push", "hash", "empty", "cancel")) {
            val commands = mutableListOf<String>()
            var bytes = byteArrayOf()
            val error = runCatching {
                RuntimeDeploymentRunner.run(directory, 9876, modes.first(),
                    push = { data, _ -> bytes = data; if (failure == "push") error("push failed") },
                    shell = { command ->
                        commands += command
                        when {
                            command.startsWith("sha256sum") -> if (failure == "hash") "" else hash(bytes)
                            command.startsWith("sh ") -> if (failure == "cancel") throw CancellationException("cancel") else ""
                            else -> ""
                        }
                    })
            }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(commands.last().startsWith("rm -f "))
            if (failure == "hash" || failure == "push") assertFalse(commands.any { it.startsWith("sh ") })
            if (failure == "empty") assertTrue(error!!.message!!.contains("ADB"))
            if (failure == "cancel") assertTrue(error is CancellationException)
        }
    }
}
