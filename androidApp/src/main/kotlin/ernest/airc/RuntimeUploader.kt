package ernest.airc

import ernest.ascrcpy.adb.AdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Uses only an existing target decoder; raw sync remains available without target changes. */
internal class RuntimeUploader(private val cache: File, private val decoders: List<String> = DECODERS) {
    companion object {
        private val cacheLock = Mutex()
        private val DECODERS = listOf("gzip -dc", "gunzip -c", "toybox gzip -dc", "toybox gunzip -c",
            "busybox gzip -dc", "busybox gunzip -c", "/data/adb/magisk/busybox gzip -dc", "/sbin/.magisk/busybox/busybox gzip -dc")
        private const val MARKER = "airc-gzip-probe"
        private val probe: String by lazy {
            val bytes = java.io.ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(MARKER.toByteArray()) } }.toByteArray()
            bytes.joinToString("") { "\\0%03o".format(it.toInt() and 255) }
        }
    }
    suspend fun upload(client: AdbClient, asset: RuntimeAsset, source: () -> InputStream,
        destination: String, privilege: RuntimePrivilege, report: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        connectionStage(ConnectionStage.PREPARE, "检查目标解压能力…")
        val decoder = decoders.firstOrNull { command ->
            require(command in DECODERS) { "无效的解压命令" }
            // Legacy Android printf can interpret octal escapes differently. An
            // existing multi-call decoder supplies its own matching printf applet.
            val printer = if (command.contains("busybox") || command.startsWith("toybox "))
                "${command.substringBefore(' ')} printf" else "printf"
            val reply = client.shell(privilege.command("$printer '%b' '$probe' | $command 2>/dev/null")).text().trim()
            reply == MARKER
        }
        if (decoder == null) {
            report("目标无可用解压工具，使用原始文件推送")
            push(client, source, asset.size, destination, "推送设备端 ${asset.abi}")
            return@withContext false
        }
        connectionStage(ConnectionStage.COMPRESS, "准备压缩设备端程序…")
        val archive = compressed(asset, source)
        if (archive.length() >= asset.size * 95 / 100) {
            report("压缩收益不足，使用原始文件推送")
            push(client, source, asset.size, destination, "推送设备端 ${asset.abi}")
            return@withContext false
        }
        val remote = "$destination.${UUID.randomUUID()}.gz"
        val quoted = RuntimePrivilege.quote(remote)
        try {
            report("压缩推送 ${managementFileSize(asset.size)} → ${managementFileSize(archive.length())}")
            push(client, archive::inputStream, archive.length(), remote, "压缩推送设备端 ${asset.abi}", 0x180)
            connectionStage(ConnectionStage.EXTRACT, "目标正在解压设备端程序…")
            val result = client.shell(privilege.command("$decoder < $quoted > ${RuntimePrivilege.quote(destination)} && chmod 700 ${RuntimePrivilege.quote(destination)} && echo airc-extracted")).text()
            check(result.lineSequence().any { it.trim() == "airc-extracted" }) { "目标解压失败，保留原服务：$result" }
            true
        } finally {
            withContext(NonCancellable) {
                runCatching { withTimeout(10_000) { client.shell(privilege.command("rm -f $quoted")) } }
            }
        }
    }
    private suspend fun push(client: AdbClient, source: () -> InputStream, size: Long, destination: String,
        label: String, mode: Int = 0x1C0,
    ) {
        connectionStage(ConnectionStage.PUSH, label)
        source().use { input ->
            val progress = currentCoroutineContext()[ConnectionDeadline]?.stream(input, size, label) ?: input
            client.push(progress, destination, mode)
        }
    }
    private suspend fun compressed(asset: RuntimeAsset, source: () -> InputStream): File = cacheLock.withLock {
        require(asset.sha256.matches(Regex("[a-f0-9]{64}")))
        check(cache.isDirectory || cache.mkdirs()) { "无法创建压缩缓存" }
        val target = File(cache, "${asset.sha256}.gz")
        val operation = currentCoroutineContext()
        if (target.isFile && runCatching {
            GZIPInputStream(target.inputStream()).use { RuntimeReleaseRepository.verify(it, asset) }
        }.getOrDefault(false)) return@withLock target
        val temporary = File(cache, "${UUID.randomUUID()}.part")
        try {
            source().use { input -> GZIPOutputStream(temporary.outputStream()).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    operation.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } }
            check(GZIPInputStream(temporary.inputStream()).use { RuntimeReleaseRepository.verify(it, asset) }) { "本地压缩缓存校验失败" }
            check(temporary.renameTo(target)) { "无法保存压缩缓存" }
            target
        } finally { temporary.delete() }
    }
}
