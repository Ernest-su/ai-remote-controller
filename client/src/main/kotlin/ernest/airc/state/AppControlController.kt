package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class AppControlController(private val scope: CoroutineScope, private val runtime: RuntimeConnection) {
    private val mutable = MutableStateFlow(AppControlState())
    val state = mutable.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private var generation = 0L
    private var job: Job? = null
    private var approval: CompletableDeferred<Boolean>? = null
    fun clear() { generation++; job?.cancel(); mutable.value = AppControlState() }
    fun confirm(accepted: Boolean) { approval?.complete(accepted) }
    private suspend fun read(target: RuntimeTransferTarget, name: String): AppControlDetails {
        val details = json.decodeFromString<AppControlDetails>(runtime.requestAt(target, "apps.control_info", buildJsonObject { put("package", name) }))
        check(details.app.packageName == name && details.state.packageName == name && details.state.userId == details.app.userId) { "应用详情对象不匹配" }
        return details
    }
    fun inspect(name: String, available: Boolean) {
        if (!available || state.value.busy) return
        val token = ++generation
        mutable.value = if (state.value.packageName == name) state.value.copy(busy = true, error = null, message = null)
            else AppControlState(packageName = name, busy = true)
        job = scope.launch {
            try { val details = read(runtime.transferTarget(), name); if (token == generation) mutable.update { it.copy(details = details) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) mutable.update { it.copy(error = e.message ?: "读取应用详情失败") } }
            finally { if (token == generation) mutable.update { it.copy(busy = false) } }
        }
    }
    fun prepare(action: String, permission: String? = null, available: Boolean, onComplete: () -> Unit = {}) {
        val details = state.value.details ?: return
        if (!available || state.value.busy) return
        check(action in listOf("force_stop", "enable", "disable", "clear_data", "grant", "revoke"))
        val token = generation
        mutable.update { it.copy(busy = true, message = null, error = null) }
        job = scope.launch {
            var proposal: AppControlProposal? = null
            var target: RuntimeTransferTarget? = null
            var submitted = false
            var pending: CompletableDeferred<Boolean>? = null
            try {
                val destination = runtime.transferTarget(); target = destination
                val p = json.decodeFromString<AppControlProposal>(runtime.requestAt(destination, "apps.prepare_control", buildJsonObject {
                    put("package", details.app.packageName); put("action", action); permission?.let { put("permission", it) }
                }))
                proposal = p
                check(p.kind == "app_control" && p.approvalRequired && p.app == details.app && p.action == action && p.permission == permission
                    && p.dataLoss == (action == "clear_data") && !p.app.system && p.app.appId >= 10000
                    && p.before.packageName == p.app.packageName && p.before.userId == p.app.userId) { "应用操作确认对象不匹配，请刷新详情" }
                runtime.checkTransferTarget(destination)
                val decision = CompletableDeferred<Boolean>(); pending = decision; approval = decision
                if (token == generation) mutable.update { it.copy(proposal = p) }
                if (!decision.await()) return@launch
                runtime.checkTransferTarget(destination); submitted = true
                if (token == generation) mutable.update { it.copy(proposal = null, committing = true) }
                var task = json.decodeFromString<TransferProgress>(runtime.approveAppControlAt(destination, buildJsonObject { put("id", p.id) }))
                val id = task.id
                while (true) {
                    check(task.id == id && task.direction == "app_control" && task.root == "apps" && task.name == p.app.packageName && task.unit == "items") { "应用操作任务对象不匹配" }
                    if (task.phase in listOf("completed", "failed", "cancelled")) break
                    delay(300); runtime.checkTransferTarget(destination)
                    task = json.decodeFromString(runtime.requestAt(destination, "task.progress", buildJsonObject { put("id", id) }))
                }
                check(task.phase == "completed") { task.error ?: "应用操作未完成" }
                val result = checkNotNull(task.result).jsonObject
                check(result["package"]?.jsonPrimitive?.content == p.app.packageName && result["userId"]?.jsonPrimitive?.int == p.app.userId
                    && result["action"]?.jsonPrimitive?.content == action && result["permission"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content } == permission
                    && result["completed"]?.jsonPrimitive?.boolean == true) { "应用操作结果对象不匹配" }
                val refreshed = read(destination, p.app.packageName)
                check(refreshed.app == p.app) { "应用安装身份已改变，请重新核对目标状态" }
                val verified = when (action) {
                    "grant", "revoke" -> refreshed.state.permissions.firstOrNull { it.name == permission }?.granted == (action == "grant")
                    "enable" -> refreshed.state.enabled && refreshed.state.enabledSetting == 1
                    "disable" -> !refreshed.state.enabled && refreshed.state.enabledSetting == 3
                    "force_stop" -> refreshed.state.stopped
                    else -> true // Data removal is reported by PackageManager, not inferred from flags.
                }
                check(verified) { "重新读取的应用状态与操作不一致，请核对目标状态" }
                if (token == generation) {
                    mutable.update { it.copy(details = refreshed, message = if (action == "clear_data") "系统已报告数据清除完成，已刷新应用状态；未测量释放空间。" else "${appControlLabel(action)}完成，已重新读取目标状态。") }
                    onComplete()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) mutable.update { it.copy(error = e.message ?: "应用操作失败；请刷新目标状态") } }
            finally {
                if (!submitted && proposal != null && target != null) withContext(NonCancellable) {
                    withTimeoutOrNull(5000) { runCatching { runtime.checkTransferTarget(target); runtime.approveAppControlAt(target, buildJsonObject { put("id", proposal.id); put("accepted", false) }) } }
                }
                if (approval === pending) approval = null
                if (token == generation) mutable.update { it.copy(busy = false, proposal = null, committing = false) }
            }
        }
    }
}
