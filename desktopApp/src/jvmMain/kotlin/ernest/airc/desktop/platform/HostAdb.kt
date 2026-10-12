package ernest.airc.desktop

import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Uses the host's existing adb server; never kills it or changes unrelated forwards. */
class HostAdb(private val executable: String = System.getenv("AIRC_ADB") ?: "adb") {
    suspend fun command(vararg arguments: String): String = runHostCommand(listOf(executable) + arguments)
    suspend fun devices(): List<String> = command("devices").lineSequence().map { it.trim().split(Regex("\\s+")) }
        .filter { it.size >= 2 && it[1] == "device" }.map { it[0] }.toList()
    suspend fun connect(endpoint: String) { command("connect", endpoint) }
    suspend fun forward(serial: String, runtimePort: Int): Int {
        require(runtimePort in 1..65535)
        return command("-s", serial, "forward", "tcp:0", "tcp:$runtimePort").trim().toInt()
    }
    suspend fun removeForward(serial: String, port: Int) { command("-s", serial, "forward", "--remove", "tcp:$port") }
    suspend fun deploy(serial: String, port: Int, preferRoot: Boolean, project: Path): String {
        val script = project.resolve("android-build-run.sh")
        check(script.toFile().isFile) { "请从项目目录启动桌面工具以部署服务" }
        return runHostCommand(listOf("bash", script.toString(), "--serial", serial, "--port", port.toString(),
            if (preferRoot) "--root" else "--no-root"), timeoutSeconds = 300, terminateChildren = true)
    }
}

internal suspend fun runHostCommand(arguments: List<String>, timeoutSeconds: Long = 30, terminateChildren: Boolean = false): String = coroutineScope {
    val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
    val output = async(Dispatchers.IO) {
        process.inputStream.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                check(buffer.size() + count <= 1024 * 1024) { "宿主命令输出超过 1 MiB" }
                buffer.write(block, 0, count)
            }
            buffer.toString(Charsets.UTF_8)
        }
    }
    try {
        withTimeout(timeoutSeconds * 1000) { while (process.isAlive) delay(50) }
        val text = output.await()
        check(process.exitValue() == 0) { text.ifBlank { "宿主命令失败" } }
        text
    } finally {
        if (process.isAlive) {
            val children = if (terminateChildren) process.descendants().use { stream -> stream.toList() }
                .filterNot { handle -> handle.info().arguments().orElse(emptyArray()).any { it == "fork-server" || it == "server" } } else emptyList()
            children.asReversed().forEach { it.destroy() }
            withContext(NonCancellable) {
                delay(100)
                if (process.isAlive) process.destroy()
                delay(100)
                children.filter { it.isAlive }.forEach { it.destroyForcibly() }
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }
}
