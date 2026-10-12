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
    fun clear() { generation++; job?.cancel(); mutable.value = MediaControlState() }
    fun choose(selection: MediaSelection) {
        if(!state.value.busy && state.value.sessions.count { it.selection() == selection && it.active } == 1) mutable.update { it.copy(selected = selection, message = null, error = null) }
    }
    private fun applyStatus(payload: MediaSessions, token: Long) {
        if(token != generation) return
        val active = payload.sessions.filter { it.active }
        val old = state.value.selected
        val selected = old?.takeIf { choice -> active.count { it.selection() == choice } == 1 }
            ?: active.firstOrNull { (it.seekSupported == true || it.customActions.isNotEmpty()) && active.count { candidate -> candidate.selection() == it.selection() } == 1 }?.selection()
        mutable.update { it.copy(sessions = active, selected = selected, seekAvailable = payload.seekAvailable, customActionAvailable = payload.customActionAvailable) }
    }
    private fun run(available: Boolean, action: suspend (Long, () -> Unit, suspend (String,JsonObject)->String) -> Unit): Boolean {
        if(!available || state.value.busy) return false
        val token = generation; mutable.update { it.copy(busy = true, error = null, message = null) }
        job = scope.launch {
            try {
                val target = runtime?.transferTarget()
                val guard = { if(runtime != null) runtime.checkTransferTarget(checkNotNull(target)) else check(token == generation) { "目标连接已改变" } }
                val bound:suspend(String,JsonObject)->String={tool,args -> if(runtime!=null)runtime.requestAt(checkNotNull(target),tool,args)else request(tool,args)}
                action(token, guard, bound)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(token == generation) mutable.update { it.copy(error = e.message ?: "播放控制失败") } }
            finally { if(token == generation) mutable.update { it.copy(busy = false) } }
        }
        return true
    }
    fun refresh(available: Boolean) = run(available) { token, guard, request ->
        guard(); val payload = json.decodeFromString<MediaSessions>(request("media.status", buildJsonObject {})); guard(); applyStatus(payload, token)
    }
    fun customAction(id: String, available: Boolean): Boolean {
        val choice = state.value.selected ?: return false
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
            if(token == generation) mutable.update { it.copy(message = "已提交播放器动作并刷新会话；请核对播放器实际结果。") }
        }
    }
    fun seek(positionMs: Long, available: Boolean): Boolean {
        val choice = state.value.selected ?: return false
        val session = state.value.sessions.singleOrNull { it.selection() == choice && it.active } ?: return false
        if(!state.value.seekAvailable || session.seekSupported != true || positionMs < 0 || session.durationMs?.takeIf { it > 0 }?.let { positionMs > it } == true) return false
        return run(available) { token, guard, request ->
            guard()
            val result = json.parseToJsonElement(request("media.seek", buildJsonObject {
                put("package", choice.packageName); put("positionMs", positionMs); choice.sessionTag?.let { put("sessionTag", it) }
            })).jsonObject
            guard(); check(result["submitted"]?.jsonPrimitive?.boolean == true && result["packageName"]?.jsonPrimitive?.content == choice.packageName && result["requestedMs"]?.jsonPrimitive?.long == positionMs) { "定位响应对象不匹配" }
            var observed: Long? = null
            repeat(8) {
                if(observed == null) {
                    delay(300); guard()
                    val payload = json.decodeFromString<MediaSessions>(request("media.status", buildJsonObject {})); guard(); applyStatus(payload, token)
                    val current = payload.sessions.singleOrNull { it.active && it.selection() == choice }
                    val position = current?.positionMs
                    if(position != null && position >= 0 && abs(position - positionMs) <= if(current.state == 3) 1500 else 500) observed = position
                }
            }
            if(token == generation) mutable.update { it.copy(message = observed?.let { value -> "已提交定位请求，播放器报告位置 $value 毫秒。" } ?: "已提交定位请求，播放器尚未报告目标位置；可刷新核对。") }
        }
    }
}
