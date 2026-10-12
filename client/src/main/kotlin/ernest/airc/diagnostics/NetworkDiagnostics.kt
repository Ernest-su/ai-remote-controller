package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Separate probe connection: never replace the user's active control session. */
class NetworkDiagnostics(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(DiagnosticState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun run(host: String, port: Int, adb: (suspend () -> String)? = null, discoveryTimeoutMs: Long = 5000, discovery: (suspend () -> DiagnosticStage)? = null) {
        if (state.value.running) return
        mutable.value = DiagnosticState(running = true)
        job = scope.launch {
            val connection = RuntimeConnection()
            suspend fun probe(name: String, action: suspend () -> String): Boolean {
                val start = System.nanoTime()
                return try {
                    val detail = withTimeout(12_000) { action() }
                    append(DiagnosticStage(name, DiagnosticOutcome.PASSED, (System.nanoTime() - start) / 1_000_000, detail)); true
                } catch (e: TimeoutCancellationException) {
                    append(DiagnosticStage(name, DiagnosticOutcome.FAILED, (System.nanoTime() - start) / 1_000_000, "探测超时，请检查目标在线状态、端口和局域网隔离。")); false
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    append(DiagnosticStage(name, DiagnosticOutcome.FAILED, (System.nanoTime() - start) / 1_000_000, diagnosticError(e))); false
                }
            }
            try {
                require(host.isNotBlank() && port in 1..65535) { "请填写有效目标地址和服务端口" }
                if (adb != null) probe("ADB", adb) else append(DiagnosticStage("ADB", DiagnosticOutcome.SKIPPED, null, "未提供已授权 ADB 会话；直连服务不需要 ADB。"))
                val http = probe("runtime HTTP") { val status = connection.status(host.trim(), port); "协议 ${status.protocol}，服务 ${status.version}" }
                if (http) probe("WebSocket") { connection.connect(host.trim(), port); "握手与协议校验通过" }
                else append(DiagnosticStage("WebSocket", DiagnosticOutcome.SKIPPED, null, "HTTP 状态失败，先修复地址、服务端口或协议。"))
                if (discovery != null) {
                    val start = System.nanoTime()
                    try { append(withTimeout(discoveryTimeoutMs) { discovery() }.copy(elapsedMs = (System.nanoTime() - start) / 1_000_000)) }
                    catch (e: TimeoutCancellationException) { append(DiagnosticStage("mDNS", DiagnosticOutcome.SKIPPED, (System.nanoTime() - start) / 1_000_000, "未观察到匹配公告；检查同一局域网及组播设置，或使用手动连接。")) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { append(DiagnosticStage("mDNS", DiagnosticOutcome.FAILED, (System.nanoTime() - start) / 1_000_000, "发现服务读取失败，请检查局域网组播和系统权限。")) }
                } else append(DiagnosticStage("mDNS", DiagnosticOutcome.SKIPPED, null, "当前平台没有提供 mDNS 探测，不能据此判断局域网发现是否正常。"))
            } catch (e: CancellationException) { append(DiagnosticStage("诊断", DiagnosticOutcome.SKIPPED, null, "诊断已取消，后续阶段未检查。")); throw e }
            catch (e: Exception) { append(DiagnosticStage("输入", DiagnosticOutcome.FAILED, null, e.message ?: "无效地址")) }
            finally { connection.close(); mutable.update { it.copy(running = false) } }
        }
    }
    private fun append(stage: DiagnosticStage) { mutable.update { it.copy(stages = it.stages + stage) } }
    fun cancel() { job?.cancel() }
}

fun diagnosticError(error: Exception): String = when (error) {
    is UnknownHostException -> "无法解析目标地址，请检查 IP 或主机名。"
    is ConnectException -> "连接被拒绝或不可达，请检查服务是否运行、端口及防火墙。"
    is SocketTimeoutException -> "连接超时，请检查目标在线状态及局域网隔离。"
    else -> "探测失败，请检查协议、ADB 授权和服务日志：${error.message?.take(160) ?: error.javaClass.simpleName}"
}
