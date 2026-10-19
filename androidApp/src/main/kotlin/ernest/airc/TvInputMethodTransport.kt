package ernest.airc

import android.content.Context
import ernest.ascrcpy.adb.AdbClient
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal const val TV_IME = "ernest.airc.ime/.RemoteInputMethod"
data class TvImeStatus(val installed: Boolean = false, val aligned: Boolean = false,
    val selected: Boolean = false, val ready: Boolean = false, val previous: String? = null)

/** No raw user text is interpolated into shell code or retained in files. */
internal fun imeCommitCommand(user: Int, text: String): String {
    val bytes = text.toByteArray(Charsets.UTF_8)
    require(text.isNotBlank() && bytes.size <= 65536) { "请输入不超过 65536 UTF-8 字节的文字" }
    return "content call --user $user --uri content://ernest.airc.ime.control --method commit --extra text:s:${Base64.getEncoder().encodeToString(bytes)}"
}

internal fun imeReplyStatus(reply: String): String =
    Regex("(?:\\{|[ ,])status=([a-z_]+)(?=[,} ])").find(reply)?.groupValues?.get(1)
        ?: error("电视输入法未返回有效回执，请核对电视后重试。")

internal interface TvImeDevice {
    suspend fun shell(command: String): String
    suspend fun push(input: java.io.InputStream, path: String)
}

internal class TvInputMethodTransport(private val context: Context, private val device: TvImeDevice,
    private val checkTarget: () -> Unit) {
    constructor(context: Context, adb: AdbClient, checkTarget: () -> Unit) : this(context, object : TvImeDevice {
        override suspend fun shell(command: String) = adb.shell(command).text()
        override suspend fun push(input: java.io.InputStream, path: String) { adb.push(input, path, 0x1A4) }
    }, checkTarget)
    private suspend fun shell(command: String): String {
        currentCoroutineContext().ensureActive(); checkTarget()
        return device.shell(command).trim().also { currentCoroutineContext().ensureActive(); checkTarget() }
    }
    private suspend fun user(): Int = shell("am get-current-user").toIntOrNull() ?: error("无法确认电视当前用户")
    private suspend fun call(user: Int, method: String): String =
        shell("content call --user $user --uri content://ernest.airc.ime.control --method $method")
    private fun expectedDigest(): String = context.assets.open("airc-input-method.apk").use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    // Used only after status() verifies the installed package; it never reads editor text.
    suspend fun focusedEditor(): TvImeStatus = readStatus(user())
    suspend fun status(): TvImeStatus = status(user())
    private suspend fun status(user: Int): TvImeStatus {
        val path = shell("pm path --user $user ernest.airc.ime").lineSequence()
            .firstOrNull { it.startsWith("package:") }?.removePrefix("package:") ?: return TvImeStatus()
        // Package paths are OS-provided; still quote them before asking the shell to hash the APK.
        val quoted = "'" + path.replace("'", "'\\''") + "'"
        val digest = shell("sha256sum $quoted").substringBefore(' ')
        if (digest != expectedDigest()) return TvImeStatus(installed = true)
        return readStatus(user)
    }
    private suspend fun readStatus(user: Int): TvImeStatus {
        val reply = call(user, "status")
        val state = imeReplyStatus(reply)
        check(state in setOf("selected", "inactive")) { "无法读取电视输入法状态" }
        val previous = Regex("(?:\\{|[ ,])previous=([A-Za-z0-9_.$/]+)").find(reply)?.groupValues?.get(1)
        return TvImeStatus(true, true, state == "selected", reply.contains("ready=true"), previous)
    }
    suspend fun enable(): TvImeStatus {
        val user = user()
        if (!status(user).aligned) {
            val path = "/data/local/tmp/airc-ime-${UUID.randomUUID()}.apk"
            try {
                checkTarget()
                context.assets.open("airc-input-method.apk").use { device.push(it, path) }
                check(shell("pm install --user $user -r -t $path").lineSequence().any { it == "Success" }) {
                    "输入法安装失败，请检查电视安装授权；签名不一致时需先在电视卸载旧的 AIRC 输入法。"
                }
            } finally {
                withContext(NonCancellable) { withTimeoutOrNull(5000) { device.shell("rm -f -- $path") } }
            }
        }
        check(status(user).aligned) { "电视输入法版本校验失败" }
        check(imeReplyStatus(call(user, "remember")) == "remembered") { "未能保存原输入法，尚未切换" }
        shell("ime enable --user $user $TV_IME")
        shell("ime set --user $user $TV_IME")
        return status(user).also { check(it.selected) { "未能启用 AIRC 输入法，请在电视系统设置中选择它。" } }
    }
    suspend fun restore(): TvImeStatus {
        val user = user()
        val before = status(user)
        check(before.aligned) { "请先安装配套输入法后再恢复，或在电视系统设置中手动选择原输入法。" }
        val previous = before.previous ?: error("没有可恢复的输入法，请在电视系统设置中选择。")
        // Restore only the IME actually saved before an explicit switch, never an arbitrary component.
        shell("ime set --user $user '${previous.replace("'", "'\\''")}'")
        val selected = shell("settings --user $user get secure default_input_method")
        check(selected == previous) { "原输入法恢复失败，请在电视系统设置中选择。" }
        return status(user)
    }
    suspend fun commit(text: String): TvImeStatus {
        val user = user()
        val before = status(user)
        check(before.aligned && before.selected) { "请先安装并启用 AIRC 输入法" }
        val reply = imeReplyStatus(shell(imeCommitCommand(user, text)))
        check(reply == "committed") {
            when (reply) {
                "no_editor" -> "请在电视上重新选中支持输入法的文字框，再发送。"
                "invalid" -> "文字格式或长度无效，草稿已保留。"
                else -> "输入法未确认提交，结果不确定，请核对电视后再发送。"
            }
        }
        return before
    }
}
