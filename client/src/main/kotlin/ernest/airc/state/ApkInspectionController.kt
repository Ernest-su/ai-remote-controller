package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class ApkInspectionController(private val scope: CoroutineScope, private val runtime: RuntimeConnection? = null, private val request: suspend (String, JsonObject) -> String) {
    constructor(scope: CoroutineScope, runtime: RuntimeConnection) : this(scope, runtime, { tool, args -> runtime.request("tool", tool, args) })
    private val mutable = MutableStateFlow(ApkInspectionState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    fun clear() { generation++; job?.cancel(); mutable.value = ApkInspectionState() }
    fun cancel() { job?.cancel() }
    fun inspect(root: String, path: String, available: Boolean) {
        if (!available || state.value.busy) return
        val token = generation
        mutable.value = ApkInspectionState(busy = true, phase = "inspecting")
        job = scope.launch {
            var taskId: String? = null
            var terminal = false
            var target: RuntimeTransferTarget? = null
            fun checkTarget() {
                if (runtime != null) runtime.checkTransferTarget(checkNotNull(target))
                else check(token == generation) { "目标连接已改变" }
            }
            suspend fun boundRequest(tool:String,args:JsonObject):String =
                if(runtime!=null)runtime.requestAt(checkNotNull(target),tool,args)else request(tool,args)
            try {
                target = runtime?.transferTarget()
                checkTarget()
                var task = json.decodeFromString<TransferProgress>(boundRequest("apps.apk_info", buildJsonObject { put("root", root); put("path", path) }))
                taskId = task.id
                while (true) {
                    checkTarget()
                    check(task.id == taskId && task.direction == "apk_inspect" && task.root == root && task.name == path) { "APK 任务对象不匹配" }
                    check(task.sizeBytes in 1..4L * 1024 * 1024 * 1024 && task.bytesTransferred in 0..task.sizeBytes) { "APK 任务进度无效" }
                    if (token == generation) mutable.value = ApkInspectionState(busy = true, phase = task.phase, bytes = task.bytesTransferred, total = task.sizeBytes)
                    if (task.phase in listOf("completed", "failed", "cancelled")) { terminal = true; break }
                    delay(300)
                    task = json.decodeFromString(boundRequest("task.progress", buildJsonObject { put("id", taskId) }))
                }
                if (task.phase == "cancelled") {
                    if (token == generation) mutable.update { it.copy(busy = false, phase = "cancelled") }
                    return@launch
                }
                check(task.phase == "completed") { task.error ?: "APK 解析失败" }
                val info = json.decodeFromJsonElement<TargetApkInfo>(checkNotNull(task.result) { "APK 信息结果缺失" })
                check(info.root == root && info.path == path) { "APK 信息对象不匹配" }
                check(info.sizeBytes == task.sizeBytes && info.sha256.matches(Regex("[a-f0-9]{64}"))) { "APK 信息响应无效" }
                if (token == generation) mutable.value = ApkInspectionState(info = info, phase = "completed", bytes = task.bytesTransferred, total = task.sizeBytes)
            } catch (e: CancellationException) {
                if (token == generation) mutable.update { it.copy(busy = false, phase = "cancelled") }
                throw e
            } catch (e: Exception) {
                if (token == generation) mutable.update { it.copy(busy = false, phase = "failed", error = e.message ?: "APK 解析失败") }
            } finally {
                if (!terminal && taskId != null) withContext(NonCancellable) {
                    withTimeoutOrNull(5000) { runCatching { checkTarget(); boundRequest("task.cancel", buildJsonObject { put("id", taskId) }) } }
                }
            }
        }
    }
}
