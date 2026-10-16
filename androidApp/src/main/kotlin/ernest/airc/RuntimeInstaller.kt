package ernest.airc

import android.content.Context
import ernest.ascrcpy.adb.AdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

/** Bundled releases are trusted through the APK; all assets are checked before and after transfer. */
class RuntimeInstaller(
    private val context: Context,
    private val repositoryFactory: (java.io.File) -> RuntimeReleaseRepository = { RuntimeReleaseRepository(it) },
) {
    companion object { const val DIRECTORY = "/data/local/tmp/airc"; const val BINARY = "$DIRECTORY/airc-runtime" }
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun install(client: AdbClient, releaseUrl: String? = null, runtimePort: Int = 9876, preferRoot: Boolean = false, report: (String) -> Unit): RuntimeRelease = withContext(Dispatchers.IO) {
        require(runtimePort in 1..65535)
        val privilege = access(client, preferRoot)
        report(if (preferRoot && !privilege.useRoot) "root 不可用，使用 ADB shell" else "运行权限：${privilege.description}")
        val operation = currentCoroutineContext()
        val repository = repositoryFactory(java.io.File(context.cacheDir, "runtime-releases"))
        val release = if (!releaseUrl.isNullOrBlank()) repository.release(releaseUrl, BuildConfig.VERSION_CODE) else try {
            context.assets.open("runtime/manifest.json").bufferedReader().use { json.decodeFromString<RuntimeRelease>(it.readText()) }
        } catch (e: Exception) { throw IllegalStateException("当前安装包未包含设备端程序，请安装完整版本", e) }
        release.validate(BuildConfig.VERSION_CODE)
        val abis = client.shell("getprop ro.product.cpu.abilist").text().trim().split(',')
        val asset = release.selectAsset(abis)
        operation.ensureActive()
        val downloaded = if (!releaseUrl.isNullOrBlank()) repository.asset(asset) { message -> operation.ensureActive(); report(message) } else null
        fun openAsset() = downloaded?.inputStream() ?: context.assets.open("runtime/${asset.file}")
        check(openAsset().use { RuntimeReleaseRepository.verify(it, asset) }) { "二进制摘要校验失败" }
        report("检查目标版本 ${release.version} · ${asset.abi}")
        check(client.shell(privilege.command("if [ ! -L $DIRECTORY ] && [ ! -L $DIRECTORY/state ] && [ ! -L $BINARY ] && [ ! -L $BINARY.next ]; then echo airc-safe; fi")).text().trim() == "airc-safe") { "部署目录或二进制不能是符号链接" }
        val remoteHash = client.shell(privilege.command("sha256sum $BINARY 2>/dev/null")).text().trim().substringBefore(' ')
        val previousRoot = client.shell(privilege.command("if [ -e $DIRECTORY/.root-mode ]; then echo root; fi")).text().trim() == "root"
        if (remoteHash != asset.sha256 || previousRoot != privilege.useRoot) {
            operation.ensureActive()
            check(client.shell(privilege.command("mkdir -p $DIRECTORY && chmod 700 $DIRECTORY && ${if (privilege.manager != RuntimePrivilege.Mode.SHELL) "chown -h 2000:2000 $DIRECTORY && " else ""}rm -f $BINARY.next && echo airc-ok")).text().contains("airc-ok")) { "无法创建部署目录" }
            report("推送设备端 ${release.version}")
            try {
                openAsset().use { client.push(it, "$BINARY.next", 0x1C0) }
                val pushed = client.shell(privilege.command("sha256sum $BINARY.next")).text().trim().substringBefore(' ')
                check(pushed == asset.sha256) { "推送后二进制摘要不一致" }
                check(client.shell(privilege.command("$BINARY.next --version")).text().trim() == release.version) { "目标程序版本与发布清单不一致，保留原服务" }
                operation.ensureActive()
                // Once replacement starts, finish readiness or rollback before honoring cancellation.
                val result = withContext(NonCancellable) {
                    deploy(client, runtimePort, privilege)
                }
                check(result.lineSequence().any { it == "airc-committed" }) {
                    if (result.contains("airc-preflight-failed")) result
                    else if (result.contains("airc-rolled-back")) "新程序启动失败，已恢复原版本：$result"
                    else "更新提交或恢复失败，请查询目标服务状态：$result"
                }
            } finally {
                withContext(NonCancellable) { runCatching { client.shell(privilege.command("rm -f $BINARY.next")) } }
            }
        }
        release
    }

    private suspend fun access(client: AdbClient, preferRoot: Boolean): RuntimePrivilege =
        RuntimePrivilege.detect(preferRoot, DIRECTORY) { client.shell(it).text() }

    private suspend fun deploy(client: AdbClient, port: Int, privilege: RuntimePrivilege): String =
        RuntimeDeploymentRunner.run(DIRECTORY, port, privilege,
            push = { bytes, path -> bytes.inputStream().use { client.push(it, path, 0x180) } },
            shell = { client.shell(it).text() })

    suspend fun start(client: AdbClient, port: Int, preferRoot: Boolean = false): Int {
        require(port in 1..65535)
        return withContext(Dispatchers.IO) {
            val privilege = access(client, preferRoot)
            val previousRoot = client.shell(privilege.command("if [ -e $DIRECTORY/.root-mode ]; then echo root; fi")).text().trim() == "root"
            check(previousRoot == privilege.useRoot) { "运行权限已变化，请使用检查 / 安装版本应用权限设置" }
            val result = client.shell(privilege.launch("$BINARY service start --state-dir $DIRECTORY/state --port $port")).text()
            check(result.contains("\"running\":true")) { "启动失败：$result" }
            json.parseToJsonElement(result.trim()).jsonObject.getValue("port").jsonPrimitive.int
        }
    }
    /** Restart the installed bytes, including permission changes, with transactional rollback. */
    suspend fun restart(client: AdbClient, port: Int, preferRoot: Boolean = false): Int = withContext(Dispatchers.IO) {
        require(port in 1..65535)
        val privilege = access(client, preferRoot)
        val staged = client.shell(privilege.command("[ ! -L $DIRECTORY ] && [ ! -L $DIRECTORY/state ] && [ ! -L $BINARY ] && [ ! -L $BINARY.next ] && cp -p $BINARY $BINARY.next && echo airc-staged")).text()
        check(staged.lineSequence().any { it == "airc-staged" }) { "无法准备目标服务重启：$staged" }
        try {
            currentCoroutineContext().ensureActive()
            val result = withContext(NonCancellable) {
                deploy(client, port, privilege)
            }
            check(result.lineSequence().any { it == "airc-committed" }) {
                if (result.contains("airc-preflight-failed")) result else "重启失败，已尝试恢复原服务：$result"
            }
            val reply = result.lineSequence().first { it.contains("\"ready\":true") }
            json.parseToJsonElement(reply).jsonObject.getValue("port").jsonPrimitive.int
        } finally {
            withContext(NonCancellable) { runCatching { client.shell(privilege.command("rm -f $BINARY.next")) } }
        }
    }
    suspend fun stop(client: AdbClient, preferRoot: Boolean = false): String = withContext(Dispatchers.IO) {
        val privilege = access(client, preferRoot)
        val result = client.shell(privilege.command("$BINARY service stop --state-dir $DIRECTORY/state")).text()
        check(result.contains("\"running\":false")) { "停止失败：$result" }
        result
    }
    suspend fun status(client: AdbClient, preferRoot: Boolean = false): String = withContext(Dispatchers.IO) {
        val privilege = access(client, preferRoot)
        client.shell(privilege.command("$BINARY service status --state-dir $DIRECTORY/state")).text()
    }
}
