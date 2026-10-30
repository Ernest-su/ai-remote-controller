package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class ScheduleController(private val scope: CoroutineScope, private val runtime: RuntimeConnection) {
    private val mutable = MutableStateFlow(ScheduleState())
    val state = mutable.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private var generation = 0
    private var job: Job? = null
    private fun sessionKey() = runtime.session.value?.let { "${it.deviceId}:${it.generation}" }
    init { scope.launch { runtime.session.collect { clear() } } }
    fun clear() { generation++; job?.cancel(); mutable.value = ScheduleState(sessionKey = sessionKey()) }
    private fun run(kind: String, args: JsonObject, expectedSession: String? = sessionKey(), quiet: Boolean = false) {
        val reading = kind == "schedule_list"
        if (reading && job?.isActive == true) return
        if (state.value.busy) return
        if (expectedSession == null || sessionKey() != expectedSession) { mutable.update { it.copy(error = "目标连接已改变，请在原设备重新确认") }; return }
        val send = runCatching { runtime.commandWriter() }.getOrElse { mutable.update { s -> s.copy(error = it.message) }; return }
        val token = ++generation
        job?.cancel()
        mutable.update { it.copy(busy = !reading, refreshing = reading && !quiet, error = if (quiet) it.error else null) }
        job = scope.launch {
            try {
                val data = withTimeout(15_000) {
                    if (!reading) send(kind, args)
                    json.decodeFromString<ScheduleList>(send("schedule_list", buildJsonObject {}))
                }
                if (token == generation) mutable.update { it.copy(data = data) }
            } catch (e: TimeoutCancellationException) {
                if (token == generation && !quiet) mutable.update { it.copy(error = "请求超时，请刷新核对任务状态；不会自动重试操作") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation && !quiet) mutable.update { it.copy(error = e.message ?: "定时操作失败；请刷新核对是否已保存") } }
            finally { if (token == generation) mutable.update { it.copy(busy = false, refreshing = false) } }
        }
    }
    fun refresh() = run("schedule_list", buildJsonObject {})
    fun refreshQuietly() = run("schedule_list", buildJsonObject {}, quiet = true)
    fun runOnce(id: String, expectedSession: String?) = run("schedule_run_once", buildJsonObject { put("id", id); put("confirmed", true) }, expectedSession)
    fun save(action: String, packageName: String?, kind: String, value: String, expectedSession: String?, volumePercent: Int? = null) = run("schedule_save", buildJsonObject {
        volumePercent?.let { put("volumePercent", it) }; put("action", action); packageName?.let { put("package", it) }; put("kind", kind); put("confirmed", true)
        when (kind) { "delay" -> put("delaySeconds", value.toLongOrNull() ?: 0); "daily" -> put("dailyTime", value); else -> put("localDateTime", value) }
    }, expectedSession)
    fun delete(id: String, expectedSession: String?) = run("schedule_delete", buildJsonObject { put("id", id) }, expectedSession)
    fun enable(id: String, enabled: Boolean, expectedSession: String?) = run("schedule_toggle", buildJsonObject { put("id", id); put("enabled", enabled); put("confirmed", true) }, expectedSession)
}
