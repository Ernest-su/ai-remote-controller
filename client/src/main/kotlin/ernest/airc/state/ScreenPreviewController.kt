package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class ScreenPreviewController(private val scope: CoroutineScope, private val runtime: RuntimeConnection) {
    private val mutable = MutableStateFlow(ScreenPreviewState())
    val state = mutable.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    @Volatile private var generation = 0L
    private fun update(token: Long, transform: (ScreenPreviewState) -> ScreenPreviewState) = synchronized(lock) { if(token == generation) mutable.update(transform) }
    private var job: Job? = null
    fun start(intervalMs: Int, available: Boolean): Boolean {
        val token = synchronized(lock) {
            if(!available || state.value.running || job?.isCompleted == false || intervalMs !in 500..5000) return false
            mutable.value = ScreenPreviewState(running = true)
            ++generation
        }
        job = scope.launch {
            val client = RuntimePreviewClient()
            var descriptor: ScreenPreviewDescriptor? = null
            val target = runCatching { runtime.transferTarget() }.getOrNull()
            try {
                checkNotNull(target) { "请先连接设备" }
                val ticket = json.decodeFromString<ScreenPreviewDescriptor>(runtime.request("tool","screen.preview_start",buildJsonObject { put("intervalMs",intervalMs); put("durationSeconds",60) }))
                descriptor = ticket
                runtime.checkTransferTarget(target)
                client.read(target.host,target.port,ticket) { frame ->
                    runtime.checkTransferTarget(target)
                    update(token) { it.copy(frame = frame) }
                }
                update(token) { it.copy(message = "预览已结束；可手动重新开始。") }
            } catch(error: CancellationException) { throw error }
            catch(error: Exception) { update(token) { it.copy(error = error.message ?: "屏幕预览失败") } }
            finally {
                client.close()
                withContext(NonCancellable) {
                    withTimeoutOrNull(5000) { runCatching {
                        if(descriptor != null && target != null) {
                            runtime.checkTransferTarget(target)
                            runtime.request("tool","screen.preview_stop",buildJsonObject { put("id",descriptor.id) })
                        }
                    } }
                }
                synchronized(lock) { job = null; mutable.update { it.copy(running = false, stopping = false) } }
            }
        }
        return true
    }
    fun stop() { synchronized(lock) { generation++; job?.cancel(); mutable.update { it.copy(running = false, stopping = job?.isCompleted == false, message = "预览已停止。") } } }
    fun clear() { synchronized(lock) { generation++; job?.cancel(); mutable.value = ScreenPreviewState(stopping = job?.isCompleted == false) } }
}
