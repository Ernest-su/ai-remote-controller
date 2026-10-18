package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun validDownloadUrl(value: String): Boolean = value.length <= 8192 && value.trim().toHttpUrlOrNull()?.let {
    it.username.isEmpty() && it.password.isEmpty()
} ?: false

class UrlDownloadController(private val scope: CoroutineScope, private val runtime: RuntimeConnection? = null, private val request: suspend (String, JsonObject) -> String) {
    constructor(scope: CoroutineScope, runtime: RuntimeConnection) : this(scope, runtime, { tool, args -> runtime.request("tool", tool, args) })
    private val mutable = MutableStateFlow(UrlDownloadState())
    val state = mutable.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private var generation = 0L
    private var job: Job? = null
    private var infoJob: Job? = null
    private var infoGeneration = 0L
    private var manualName = false
    fun editUrl(value: String) {
        if (state.value.busy || value == state.value.url) return
        infoGeneration++; infoJob?.cancel()
        val inferred = value.trim().toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.takeIf(::validTargetFileName).orEmpty()
        mutable.update { it.copy(url = value, name = if (manualName) it.name else inferred,
            infoReady = false, infoLoading = false, fileSize = null, infoError = null, phase = "idle", bytes = 0, total = null, destination = null, error = null) }
    }
    fun editName(value: String) { if(!state.value.busy) { manualName = true; mutable.update { it.copy(name = value) } } }
    fun inspect(available: Boolean) {
        infoJob?.cancel()
        val token = ++infoGeneration
        val url = state.value.url.trim()
        if (!available || state.value.busy || !validDownloadUrl(url)) { mutable.update { it.copy(infoLoading = false) }; return }
        mutable.update { it.copy(infoLoading = true, infoReady = false, fileSize = null, infoError = null) }
        infoJob = scope.launch {
            try {
                delay(400)
                val target = runtime?.transferTarget()
                val args = buildJsonObject { put("url", url) }
                val output = withTimeout(12000) { if (runtime != null) runtime.requestAt(checkNotNull(target), "files.url_info", args) else request("files.url_info", args) }
                val info = json.decodeFromString<UrlFileInfo>(output)
                check(validTargetFileName(info.name) && (info.sizeBytes?.let { it >= 0 } != false)) { "网址文件信息无效" }
                if (runtime != null) runtime.checkTransferTarget(checkNotNull(target))
                if (token == infoGeneration) mutable.update { it.copy(name = if (manualName) it.name else info.name,
                    fileSize = info.sizeBytes, infoReady = true, infoLoading = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (token == infoGeneration) mutable.update { it.copy(infoLoading = false, infoError = "暂时无法获取文件信息，仍可尝试下载。") }
            }
        }
    }
    fun directory(root: String, path: String) {
        if(!state.value.busy && root in listOf("downloads", "inbox", "full") && (path.isEmpty() || path.split('/').all(::validTargetFileName)))
            mutable.update { it.copy(root = root, directory = path) }
    }
    fun clear() { generation++; job?.cancel(); infoGeneration++; infoJob?.cancel(); manualName = false; mutable.value = UrlDownloadState() }
    fun cancel() { job?.cancel() }
    fun start(available: Boolean) {
        val draft = state.value
        if(!available || draft.busy || !validDownloadUrl(draft.url) || (draft.name.isNotBlank() && !validTargetFileName(draft.name.trim()))) return
        infoGeneration++; infoJob?.cancel()
        val token = generation
        mutable.value = draft.copy(busy = true, infoLoading = false, phase = "connecting", bytes = 0, total = null, destination = null, error = null)
        job = scope.launch {
            var id: String? = null; var terminal = false; var userCancelled = false; var target: RuntimeTransferTarget? = null
            fun checkTarget() { check(token == generation) { "目标连接已改变" }; if(runtime != null) runtime.checkTransferTarget(checkNotNull(target)) }
            suspend fun query(tool: String, args: JsonObject): String = if(runtime != null) runtime.requestAt(checkNotNull(target), tool, args) else request(tool, args)
            try {
                target = runtime?.transferTarget(); checkTarget()
                var task = json.decodeFromString<TransferProgress>(query("files.download_url", buildJsonObject {
                    put("url", draft.url.trim()); put("root", draft.root); put("directory", draft.directory)
                    if(draft.name.isNotBlank()) put("name", draft.name.trim())
                }))
                id = task.id
                var previous = 0L
                while(true) {
                    checkTarget()
                    val result = task.result?.jsonObject
                    val path = result?.get("path")?.jsonPrimitive?.content
                    check(task.id == id && task.direction == "url_download" && task.root == draft.root && task.unit == "bytes" && validTargetFileName(task.name)) { "下载任务对象不匹配" }
                    check(path == listOf(draft.directory, task.name).filter { it.isNotEmpty() }.joinToString("/") && (draft.name.isBlank() || task.name == draft.name.trim())) { "下载保存位置不匹配" }
                    val total = checkNotNull(result)["totalBytes"]?.jsonPrimitive?.longOrNull
                    check(task.bytesTransferred in previous..1099511627776L && (total == null || total in task.bytesTransferred..1099511627776L)) { "下载进度无效" }
                    previous = task.bytesTransferred
                    mutable.update { it.copy(phase = task.phase, bytes = task.bytesTransferred, total = total, destination = "${fileRootTitle(task.root)}/$path") }
                    if(task.phase in listOf("completed", "failed", "cancelled")) { terminal = true; break }
                    check(task.phase in listOf("connecting", "downloading", "committing")) { "下载状态无效" }
                    delay(300)
                    task = json.decodeFromString(query("task.progress", buildJsonObject { put("id", id) }))
                }
                check(task.phase != "failed") { task.error ?: "下载失败" }
                if(task.phase == "completed") check(state.value.total == state.value.bytes) { "下载完成长度不匹配" }
            } catch(e: CancellationException) { userCancelled = true; if(token == generation) mutable.update { it.copy(phase = "cancelling") }; throw e }
            catch(e: Exception) { if(token == generation) mutable.update { it.copy(phase = "failed", error = e.message ?: "下载失败") } }
            finally {
                if(!terminal && id != null) withContext(NonCancellable) {
                    try {
                        var cleanup = withTimeout(5000) { json.decodeFromString<TransferProgress>(query("task.cancel", buildJsonObject { put("id", id) })) }
                        while(userCancelled && token == generation) {
                            check(cleanup.id == id && cleanup.direction == "url_download" && cleanup.root == draft.root) { "取消下载任务对象不匹配" }
                            val result = cleanup.result?.jsonObject
                            val path = result?.get("path")?.jsonPrimitive?.content
                            check(validTargetFileName(cleanup.name) && path == listOf(draft.directory, cleanup.name).filter { it.isNotEmpty() }.joinToString("/")) { "取消下载保存位置不匹配" }
                            val total = result?.get("totalBytes")?.jsonPrimitive?.longOrNull
                            check(cleanup.bytesTransferred in state.value.bytes..1099511627776L && (total == null || total in cleanup.bytesTransferred..1099511627776L)) { "取消下载进度无效" }
                            check(cleanup.phase in listOf("connecting", "downloading", "committing", "completed", "failed", "cancelled")) { "取消下载状态无效" }
                            if(cleanup.phase == "completed") check(total == cleanup.bytesTransferred) { "下载完成长度不匹配" }
                            mutable.update { it.copy(phase = if(cleanup.phase == "committing") "committing" else if(cleanup.phase in listOf("completed", "failed", "cancelled")) cleanup.phase else "cancelling",
                                bytes = cleanup.bytesTransferred, total = total, destination = "${fileRootTitle(cleanup.root)}/$path", error = cleanup.error) }
                            if(cleanup.phase in listOf("completed", "failed", "cancelled")) break
                            delay(300)
                            cleanup = withTimeout(5000) { json.decodeFromString(query("task.progress", buildJsonObject { put("id", id) })) }
                        }
                    } catch(e: Exception) {
                        if(userCancelled && token == generation) mutable.update { it.copy(phase = "failed", error = "未能确认下载取消：${e.message}") }
                    }
                }
                if(token == generation) mutable.update {
                    if(userCancelled && id == null) it.copy(busy = false, phase = "failed", error = "下载请求尚未返回，未能确认取消，请检查目标目录。")
                    else it.copy(busy = false)
                }
            }
        }
    }
}
