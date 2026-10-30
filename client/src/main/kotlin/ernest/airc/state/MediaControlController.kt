package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.math.abs

class MediaControlController(private val scope: CoroutineScope, private val runtime: RuntimeConnection? = null, private val request: suspend (String, JsonObject) -> String) {
    constructor(scope: CoroutineScope, runtime: RuntimeConnection) : this(scope, runtime, { tool, args -> runtime.request("tool", tool, args) })
    private val mutable = MutableStateFlow(MediaControlState())
    val state = mutable.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private var generation = 0L
    private var job: Job? = null
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    private var snapshotTarget: RuntimeTransferTarget? = null
    fun clear() { generation++; snapshotTarget = null; job?.cancel(); stopRefreshing(); mutable.value = MediaControlState() }
    fun stopRefreshing() { refreshGeneration++; refreshJob?.cancel(); refreshJob = null; mutable.update { it.copy(refreshing = false, manualRefreshing = false) } }
    suspend fun observe() {
        try {
            while (currentCoroutineContext().isActive) {
                refresh(true, quiet = true)
                refreshJob?.join()
                delay(3000)
            }
        } finally { stopRefreshing() }
    }
    fun choose(selection: MediaSelection) {
        if(!state.value.busy && state.value.sessions.count { it.selection() == selection && it.active } == 1) mutable.update { it.copy(selected = selection, message = null, error = null, pendingSeekMs = null) }
    }
    private fun applyStatus(payload: MediaSessions, token: Long) {
        if(token != generation) return
        val active = payload.sessions.filter { it.active }
        val old = state.value.selected
        val selected = old?.takeIf { choice -> active.count { it.selection() == choice } == 1 }
            ?: active.sortedBy { when (it.state) { 3 -> 0; 2 -> 1; else -> 2 } }
                .firstOrNull { it.selection() != null && active.count { candidate -> candidate.selection() == it.selection() } == 1 }?.selection()
        mutable.update { previous ->
            val pending = previous.pendingSeekMs
            val current = active.singleOrNull { it.selection() == selected }
            val message = if (pending != null && selected != previous.selected) null else if (pending != null && selected == previous.selected) {
                if (current != null && observedSeek(current, pending)) "已跳转至 ${formatPlaybackTime(checkNotNull(current.positionMs))}"
                else current?.positionMs?.takeIf { it >= 0 }?.let { "定位已发送，播放器报告 ${formatPlaybackTime(it)}" } ?: "定位已发送，暂未读取到位置"
            } else previous.message
            previous.copy(sessions = active, selected = selected, seekAvailable = payload.seekAvailable,
                customActionAvailable = payload.customActionAvailable, transportAvailable = payload.transportAvailable,
                loaded = true, refreshError = null, pendingSeekMs = null, message = message)
        }
    }
    private fun run(available: Boolean, action: suspend (Long, () -> Unit, suspend (String,JsonObject)->String) -> Unit): Boolean {
        if(!available || state.value.busy) return false
        val connection = runtime
        val target = if (connection != null) runCatching { connection.transferTarget() }.getOrNull() ?: return false else null
        if (runtime != null && target != snapshotTarget) return false
        stopRefreshing()
        val token = generation; mutable.update { it.copy(busy = true, error = null, message = null, pendingSeekMs = null) }
        job = scope.launch {
            try {
                val guard = { if(runtime != null) runtime.checkTransferTarget(checkNotNull(target)) else check(token == generation) { "目标连接已改变" } }
                val bound:suspend(String,JsonObject)->String={tool,args -> if(runtime!=null)runtime.requestAt(checkNotNull(target),tool,args)else request(tool,args)}
                action(token, guard, bound)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(token == generation) mutable.update { it.copy(error = e.message ?: "播放控制失败", pendingSeekMs = null) } }
            finally { if(token == generation) mutable.update { it.copy(busy = false) } }
        }
        return true
    }
    fun refresh(available: Boolean, quiet: Boolean = false): Boolean {
        if (!available || state.value.busy) return false
        if (state.value.refreshing) {
            if (quiet) return false
            stopRefreshing()
        }
        val token = generation
        val refreshToken = ++refreshGeneration
        mutable.update { it.copy(refreshing = true, manualRefreshing = !quiet, refreshError = if (quiet) it.refreshError else null,
            error = if (quiet) it.error else null, message = if (quiet) it.message else null) }
        refreshJob = scope.launch {
            fun current() = token == generation && refreshToken == refreshGeneration
            try {
                val target = runtime?.transferTarget()
                val output = withTimeout(8000) {
                    if (runtime != null) runtime.requestAt(checkNotNull(target), "media.status", buildJsonObject {})
                    else request("media.status", buildJsonObject {})
                }
                if (runtime != null) runtime.checkTransferTarget(checkNotNull(target))
                if (current()) {
                    snapshotTarget = target
                    applyStatus(json.decodeFromString<MediaSessions>(output), token)
                    mutable.update { it.copy(refreshError = null) }
                }
            } catch (_: TimeoutCancellationException) {
                if (current()) mutable.update { it.copy(refreshError = if (it.loaded) "播放状态更新超时，显示上次结果" else "读取播放状态超时，请重试") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current()) mutable.update { it.copy(refreshError = if (it.loaded) "播放状态更新失败，显示上次结果" else "无法读取播放状态，请重试") } }
            finally { if (current()) mutable.update { it.copy(refreshing = false, manualRefreshing = false) } }
        }
        return true
    }
    fun command(command: String, available: Boolean, expectedSelection: MediaSelection? = state.value.selected): Boolean {
        val choice = expectedSelection?.takeIf { it == state.value.selected } ?: return false
        val session = state.value.sessions.singleOrNull { it.active && it.selection() == choice } ?: return false
        if (!state.value.transportAvailable || !session.supportsCommand(command)) return false
        return run(available) { token, guard, request ->
            guard()
            val result = json.parseToJsonElement(request("media.command", buildJsonObject {
                put("package", choice.packageName); put("command", command); choice.sessionTag?.let { put("sessionTag", it) }
            })).jsonObject
            guard()
            check(result["submitted"]?.jsonPrimitive?.boolean == true && result["packageName"]?.jsonPrimitive?.content == choice.packageName &&
                result["command"]?.jsonPrimitive?.content == command && result["sessionTag"]?.jsonPrimitive?.contentOrNull == choice.sessionTag) { "播放操作响应对象不匹配" }
            val payload = json.decodeFromString<MediaSessions>(request("media.status", buildJsonObject {}))
            guard(); applyStatus(payload, token)
            if (token == generation) mutable.update { it.copy(message = null) }
        }
    }
    fun customAction(id: String, available: Boolean, expectedSelection: MediaSelection? = state.value.selected): Boolean {
        val choice = expectedSelection?.takeIf { it == state.value.selected } ?: return false
        val session = state.value.sessions.singleOrNull { it.active && it.selection() == choice } ?: return false
        if(!state.value.customActionAvailable || session.customActions.count { it.id == id } != 1) return false
        return run(available) { token, guard, request ->
            guard()
            val result = json.parseToJsonElement(request("media.custom_action", buildJsonObject {
                put("package", choice.packageName); put("action", id); choice.sessionTag?.let { put("sessionTag", it) }
            })).jsonObject
            guard()
            check(result["submitted"]?.jsonPrimitive?.boolean == true && result["packageName"]?.jsonPrimitive?.content == choice.packageName && result["action"]?.jsonPrimitive?.content == id) { "播放器动作响应对象不匹配" }
            val payload = json.decodeFromString<MediaSessions>(request("media.status", buildJsonObject {}))
            guard(); applyStatus(payload, token)
            if(token == generation) mutable.update { it.copy(message = "已发送播放器操作，请以实际播放结果为准") }
        }
    }
    fun seek(positionMs: Long, available: Boolean, expectedSelection: MediaSelection? = state.value.selected): Boolean {
        val choice = expectedSelection?.takeIf { it == state.value.selected } ?: return false
        val session = state.value.sessions.singleOrNull { it.selection() == choice && it.active } ?: return false
        if(!state.value.seekAvailable || session.seekSupported != true || positionMs < 0 || session.durationMs?.takeIf { it > 0 }?.let { positionMs > it } == true) return false
        return run(available) { token, guard, request ->
            guard()
            val result = json.parseToJsonElement(request("media.seek", buildJsonObject {
                put("package", choice.packageName); put("positionMs", positionMs); choice.sessionTag?.let { put("sessionTag", it) }
            })).jsonObject
            guard(); check(result["submitted"]?.jsonPrimitive?.boolean == true && result["packageName"]?.jsonPrimitive?.content == choice.packageName && result["requestedMs"]?.jsonPrimitive?.long == positionMs) { "定位响应对象不匹配" }
            val reported = runCatching { json.decodeFromJsonElement<MediaSession>(result.getValue("session")) }
                .getOrElse { throw IllegalStateException("定位响应缺少有效会话快照") }
            check(reported.active && reported.selection() == choice) { "定位响应会话不匹配" }
            if (token == generation) mutable.update { previous ->
                val observed = observedSeek(reported, positionMs)
                previous.copy(sessions = previous.sessions.map { if (it.selection() == choice) reported else it },
                    pendingSeekMs = if (observed) null else positionMs,
                    message = if (observed) "已跳转至 ${formatPlaybackTime(checkNotNull(reported.positionMs))}" else "跳转已发送，等待播放器更新位置")
            }
        }
    }
    private fun observedSeek(session: MediaSession, requested: Long): Boolean {
        val position = session.positionMs ?: return false
        return position >= 0 && abs(position - requested) <= if (session.state == 3) 1500 else 500
    }

}
