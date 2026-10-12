package ernest.airc.desktop

import kotlinx.coroutines.*
import kotlin.test.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class HostCommandTest {
    @Test fun failedCommandAndOutputLimitAreReported(): Unit = runBlocking {
        assertEquals("actual-ok", runHostCommand(listOf("sh", "-c", "printf actual-ok")))
        assertFails { runHostCommand(listOf("sh", "-c", "exit 7")) }
        assertFails { runHostCommand(listOf("sh", "-c", "yes bounded-output")) }
    }

    @Test fun cancellationTerminatesOnlyTheOwnedCommandTree(): Unit = runBlocking {
        val directory = Files.createTempDirectory("airc-command-cancel-")
        val childPid = directory.resolve("child-pid")
        try {
            val script = directory.resolve("wait.sh")
            Files.writeString(script, "#!/bin/sh\nsleep 60 &\necho ${'$'}! > '${childPid}'\nwait\n")
            val job = launch { runHostCommand(listOf("sh", script.toString()), terminateChildren = true) }
            withTimeout(5000) { while (!Files.exists(childPid)) delay(20) }
            val child = ProcessHandle.of(Files.readString(childPid).trim().toLong()).get()
            withTimeout(5000) { job.cancelAndJoin() }
            assertFalse(child.isAlive, "Owned sleep process remained alive after cancellation")
        } finally { directory.toFile().deleteRecursively() }
    }
}
