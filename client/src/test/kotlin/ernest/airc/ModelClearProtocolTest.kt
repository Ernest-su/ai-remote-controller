package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ModelClearProtocolTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun clearRemovesTargetCredentialsPersistsAndRejectsAnOldConnection(): Unit = runBlocking {
        val binary = File(requireNotNull(System.getProperty("airc.hostRuntime")))
        val port = ServerSocket(0).use { it.localPort }
        val directory = temporary.newFolder("model-clear-state")
        val log = temporary.newFile("runtime.log")
        fun start() = ProcessBuilder(binary.absolutePath, "--state-dir", directory.path, "--port", "$port", "--no-mdns")
            .redirectErrorStream(true).redirectOutput(log).start()
        fun stop(process: Process) { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor() } }
        var process = start()
        val runtime = RuntimeConnection()
        suspend fun connect() { withTimeout(10000) { while (runCatching { runtime.connect("127.0.0.1", port) }.isFailure) { check(process.isAlive) { log.readText() }; delay(50) } } }
        suspend fun configured() = runtime.status("127.0.0.1", port).modelConfigured
        val profile = ModelProfile("https://model-fixture.invalid/v1", "fixture-only-key", "fixture-model")
        try {
            connect()
            runtime.configurationWriter()(profile)
            assertTrue(configured())
            val old = runtime.commandWriter()
            runtime.disconnect(); connect()
            assertFailsWith<IllegalStateException> { old("clear_model", buildJsonObject {}) }
            assertTrue(configured())
            runtime.commandWriter()("clear_model", buildJsonObject {})
            assertFalse(configured())
            assertFalse(File(directory, "model.json").exists())
            runtime.commandWriter()("clear_model", buildJsonObject {}) // idempotent
            runtime.disconnect(); stop(process); process = start(); connect()
            assertFalse(configured())
            runtime.configurationWriter()(profile)
            assertTrue(configured())
        } finally { runtime.close(); stop(process) }
    }
}
