package ernest.airc

import kotlinx.coroutines.currentCoroutineContext
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal enum class ConnectionStage(val label: String, val maximumMillis: Long, val idleMillis: Long = maximumMillis) {
    CONNECT("连接 ADB 与等待授权", 45_000),
    PAIR("无线调试配对", 45_000),
    QR("等待二维码扫描", 180_000),
    DISCOVER("发现无线调试端口", 25_000),
    PREPARE("检查目标运行环境", 60_000),
    DOWNLOAD("下载配套程序", 300_000, 30_000),
    VERIFY("校验本地程序", 60_000),
    WAIT("等待目标部署收尾", 100_000),
    COMPRESS("准备本地压缩程序", 120_000),
    EXTRACT("目标解压设备端程序", 60_000),
    PUSH("推送设备端程序", 600_000, 30_000),
    CHECK("检查目标程序版本", 30_000),
    START("启动目标服务与必要时回滚", 100_000),
    RUNTIME("连接遥控服务", 25_000),
}

/** An independent watchdog can release the UI even while a blocking ADB call unwinds. */
internal class ConnectionDeadline(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val report: (String) -> Unit = {},
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConnectionDeadline>
    private val started = clock()
    private var stage = ConnectionStage.CONNECT
    private var entered = started
    private var lastProgress = started
    private var transferred = 0L
    private var reported = Long.MIN_VALUE
    private var waitingReported = false

    @Synchronized fun begin(next: ConnectionStage, message: String = next.label) {
        stage = next; entered = clock(); lastProgress = entered; transferred = 0; reported = Long.MIN_VALUE; waitingReported = false
        report(message)
    }
    @Synchronized fun progress(bytes: Long, total: Long, label: String) {
        if (bytes > transferred) { transferred = bytes; lastProgress = clock() }
        val now = clock()
        if (reported == Long.MIN_VALUE || now - reported >= 500 || bytes == total) {
            reported = now
            val speed = if (now > entered) bytes * 1000 / (now - entered) else 0
            report("$label · ${managementFileSize(bytes)} / ${managementFileSize(total)} · ${managementFileSize(speed)}/s")
        }
    }
    @Synchronized fun pulse() { lastProgress = clock() }
    @Synchronized fun reportWaiting() {
        if (!waitingReported && stage == ConnectionStage.CONNECT && clock() - entered >= 8_000) {
            waitingReported = true
            report("正在等待被控端 ADB 响应…")
        }
    }
    @Synchronized fun retryReason(timedOut: Boolean = false): String = when (stage) {
        ConnectionStage.CONNECT -> if (timedOut) "被控端 ADB 未响应" else "被控端 ADB 连接失败"
        ConnectionStage.RUNTIME -> if (timedOut) "遥控服务未响应" else "遥控服务连接失败"
        ConnectionStage.PUSH -> "设备端程序传输未完成"
        ConnectionStage.START -> "被控端服务未就绪"
        else -> "${stage.label}未完成"
    }
    @Synchronized fun timeoutMessage() = "连接超时（${stage.label}）：阶段操作未及时完成，请检查目标状态或重试"
    @Synchronized fun failure(totalLimitMillis: Long): String? {
        val now = clock()
        val reason = when {
            now - started >= totalLimitMillis -> "已达到连接总时长上限"
            now - entered >= stage.maximumMillis -> "已达到此阶段时长上限"
            now - lastProgress >= stage.idleMillis -> "连续 ${stage.idleMillis / 1000} 秒无传输进展"
            else -> return null
        }
        return "连接超时（${stage.label}）：$reason，请检查目标状态或选择其他设备重试"
    }
    fun stream(source: InputStream, total: Long, label: String): InputStream = object : FilterInputStream(source) {
        private var bytes = 0L
        init { progress(0, total, label) }
        override fun read(): Int = `in`.read().also { if (it >= 0) { bytes++; progress(bytes, total, label) } }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also {
            if (it > 0) { bytes += it; progress(bytes, total, label) }
        }
    }
}

internal suspend fun connectionStage(stage: ConnectionStage, message: String = stage.label) {
    currentCoroutineContext()[ConnectionDeadline]?.begin(stage, message)
}
