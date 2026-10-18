package ernest.ascrcpy.scrcpy

import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbChannel
import java.io.InputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.*

/** A control-only session: no video, audio, automatic clipboard reads or retained text. */
class ScrcpyTextInput(private val adb: AdbClient) {
    suspend fun paste(server: InputStream, text: String, checkTarget: () -> Unit) = coroutineScope {
        val payload = clipboardPasteMessage(text)
        val path = "/data/local/tmp/airc-text-${java.util.UUID.randomUUID()}.jar"
        var shell: AdbChannel? = null
        var control: AdbChannel? = null
        var drain: Job? = null
        try {
            adb.push(server, path, 0x1A4)
            checkTarget()
            val socket = "scrcpy_${kotlin.random.Random.nextInt(1, Int.MAX_VALUE).toString(16).padStart(8, '0')}"
            shell = adb.open("shell:CLASSPATH=$path app_process / com.genymobile.scrcpy.Server 4.0 scid=${socket.removePrefix("scrcpy_")} tunnel_forward=true video=false audio=false control=true clipboard_autosync=false send_device_meta=false send_dummy_byte=false cleanup=false")
            val serverChannel = shell
            drain = launch { drainServerOutput(serverChannel) }
            withTimeout(8000) {
                while (control == null) {
                    ensureActive(); checkTarget()
                    try { control = adb.open("localabstract:$socket") }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { delay(100) }
                }
            }
            val channel = checkNotNull(control)
            checkTarget()
            channel.write(payload)
            // With autosync disabled, the only server message is our clipboard ACK.
            val reply = ByteArray(9)
            var offset = 0
            withTimeout(5000) {
                while (offset < reply.size) {
                    val count = channel.read(reply, offset, reply.size - offset)
                    check(count > 0) { "电视输入连接已关闭，结果不确定，请核对电视。" }
                    offset += count
                }
            }
            check(reply[0].toInt() == 1 && ByteBuffer.wrap(reply, 1, 8).long == 1L) { "电视输入回执无效，请核对电视。" }
            checkTarget()
        } finally {
            runCatching { control?.close() }; runCatching { shell?.close() }; drain?.cancel()
            withContext(NonCancellable) {
                withTimeoutOrNull(5000) { drain?.join(); runCatching { adb.shell("rm -f -- $path") } }
            }
        }
    }
}

internal fun clipboardPasteMessage(text: String): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    require(bytes.isNotEmpty() && bytes.size <= 65536) { "文字需为 1–65536 个 UTF-8 字节" }
    return ByteBuffer.allocate(14 + bytes.size).put(9.toByte()).putLong(1).put(1.toByte()).putInt(bytes.size).put(bytes).array()
}
