package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.util.UUID

/** One bounded, connection-bound stream per key. Only the target generates key repeats. */
class RemoteKeyController(
    private val scope: CoroutineScope,
    private val writer: () -> (suspend (String, JsonObject) -> String),
    private val onError: (String) -> Unit = {},
) : RemoteKeySender {
    constructor(scope: CoroutineScope, runtime: RuntimeConnection, onError: (String) -> Unit = {}) :
        this(scope, runtime::toolWriter, onError)
    private class Press(val key: String, val id: String, val epoch: Long) {
        var ended = false
        var cancelled = false
        val wake = Channel<Unit>(Channel.CONFLATED)
        lateinit var job: Job
    }
    private val lock = Any()
    private val active = mutableMapOf<String, Press>()
    private val tails = mutableMapOf<String, Job>()
    private var epoch = 0L

    override fun down(key: String): String? = synchronized(lock) {
        if (active.size >= 16) { onError("按键队列已满，请稍后重试"); return null }
        val send = try { writer() } catch (error: Exception) { onError(error.message ?: "请先连接设备"); return null }
        val press = Press(key, UUID.randomUUID().toString(), epoch)
        val previous = tails[key]
        active[press.id] = press
        press.job = scope.launch(start = CoroutineStart.LAZY) {
            var submitted = false
            var failure: Exception? = null
            suspend fun edge(action: String) {
                val reply = Json.parseToJsonElement(withTimeout(1000) {
                    send("remote.key", buildJsonObject { put("key", key); put("action", action); put("pressId", press.id) })
                }).jsonObject
                check(reply["pressId"]?.jsonPrimitive?.content == press.id &&
                    reply["operation"]?.jsonPrimitive?.content == action && reply["submitted"]?.jsonPrimitive?.boolean == true &&
                    reply["released"]?.jsonPrimitive?.boolean == (action == "up" || action == "cancel")) { "遥控按键响应不匹配" }
            }
            try {
                previous?.join()
                val (ended, cancelled) = synchronized(lock) { press.ended to press.cancelled }
                if (cancelled) return@launch
                if (ended) {
                    // Preserve queued quick taps; a canceled queued press never sends DOWN.
                    withTimeout(1000) { send("remote.key", buildJsonObject { put("key", key) }) }
                    return@launch
                }
                submitted = true
                edge("down")
                withTimeout(120_000) {
                    while (!synchronized(lock) { press.ended }) {
                        withTimeoutOrNull(250) { press.wake.receive() }
                        if (!synchronized(lock) { press.ended }) edge("keep")
                    }
                }
            } catch (error: TimeoutCancellationException) { failure = IllegalStateException("遥控按键请求或按住时间超时", error) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure = error }
            finally {
                if (submitted) withContext(NonCancellable) {
                    try { edge(if (failure == null && synchronized(lock) { press.ended && !press.cancelled }) "up" else "cancel") }
                    catch (error: Exception) { if (failure == null) failure = error }
                }
                synchronized(lock) {
                    active.remove(press.id)
                    if (tails[key] === press.job) tails.remove(key)
                    if (press.epoch == epoch) failure?.let { onError(it.message ?: "遥控按键失败") }
                }
            }
        }
        tails[key] = press.job
        press.job.start()
        press.id
    }
    override fun up(pressId: String, cancelled: Boolean) = synchronized(lock) {
        active[pressId]?.let { it.ended = true; it.cancelled = it.cancelled || cancelled; it.wake.trySend(Unit) }
        Unit
    }
    override fun cancelAll() = synchronized(lock) {
        epoch++
        active.values.forEach { it.ended = true; it.cancelled = true; it.wake.trySend(Unit) }
    }
}
