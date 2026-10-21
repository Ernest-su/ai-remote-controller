package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class StorageAnalysisController(private val scope: CoroutineScope, private val runtime: RuntimeConnection? = null, private val request: suspend (String, JsonObject) -> String) {
    constructor(scope: CoroutineScope, runtime: RuntimeConnection) : this(scope, runtime, { tool, args -> runtime.request("tool", tool, args) })
    private val mutable = MutableStateFlow(StorageAnalysisState())
    val state = mutable.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private var generation = 0L
    private var job: Job? = null
    fun clear() { generation++; job?.cancel(); mutable.value = StorageAnalysisState() }
    fun cancel() { job?.cancel() }
    fun fileDeleted(root: String, path: String) {
        mutable.update { current ->
            val report=current.report
            if (report == null || report.root != root || report.largestFiles.none { it.path == path }) current
            else current.copy(outdated=true,report=report.copy(largestFiles=report.largestFiles.filterNot { it.path == path }))
        }
    }
    fun analyze(root: String, path: String, available: Boolean) {
        if (!available || state.value.busy) return
        val token = generation
        mutable.value = StorageAnalysisState(noticeId = java.util.UUID.randomUUID().toString(), busy = true, root = root, path = path, phase = "scanning")
        job = scope.launch {
            var id: String? = null; var terminal = false; var target: RuntimeTransferTarget? = null
            fun checkTarget() { if(runtime != null) runtime.checkTransferTarget(checkNotNull(target)) else check(token == generation) { "目标连接已改变" } }
            suspend fun boundRequest(tool:String,args:JsonObject):String =
                if(runtime!=null)runtime.requestAt(checkNotNull(target),tool,args)else request(tool,args)
            try {
                target = runtime?.transferTarget(); checkTarget()
                var task = json.decodeFromString<TransferProgress>(boundRequest("storage.analyze", buildJsonObject { put("root", root); put("path", path) }))
                id = task.id
                while (true) {
                    checkTarget()
                    check(task.id == id && task.direction == "storage_scan" && task.root == root && task.name == path && task.unit == "items") { "存储分析对象不匹配" }
                    check(task.sizeBytes in 1..100000 && task.bytesTransferred in 0..task.sizeBytes) { "存储分析进度无效" }
                    if(token == generation) mutable.update { it.copy(phase = task.phase, visited = task.bytesTransferred, limit = task.sizeBytes) }
                    if(task.phase in listOf("completed", "failed", "cancelled")) { terminal = true; break }
                    delay(300); task = json.decodeFromString(boundRequest("task.progress", buildJsonObject { put("id", id) }))
                }
                if(task.phase == "cancelled") { if(token == generation) mutable.update { it.copy(busy = false, phase = "cancelled") }; return@launch }
                check(task.phase == "completed") { task.error ?: "存储分析失败" }
                val report = json.decodeFromJsonElement<StorageReport>(checkNotNull(task.result))
                check(report.root == root && report.path == path && report.entriesVisited == task.bytesTransferred && report.totals.files in 0..report.entriesVisited && report.largestFiles.size <= 20 && report.errors.size <= 8) { "存储报告响应无效" }
                if(token == generation) mutable.update { it.copy(busy = false, phase = "completed", report = report) }
            } catch(e: CancellationException) { if(token == generation) mutable.update { it.copy(phase = "cancelling") }; throw e }
            catch(e: Exception) { if(token == generation) mutable.update { it.copy(phase = "failed", error = e.message ?: "存储分析失败") } }
            finally {
                if(!terminal && id != null) withContext(NonCancellable) {
                    withTimeoutOrNull(5000) { runCatching { checkTarget(); boundRequest("task.cancel", buildJsonObject { put("id", id) }) } }
                }
                if(token == generation) mutable.update { it.copy(busy = false, phase = if(it.phase == "cancelling") "cancelled" else it.phase) }
            }
        }
    }
}
