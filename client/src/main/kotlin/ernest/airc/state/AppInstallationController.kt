package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class AppInstallationController(private val scope: CoroutineScope, private val runtime: RuntimeConnection) {
    private val mutable = MutableStateFlow(AppInstallationState())
    val state = mutable.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private var generation = 0L
    private var job: Job? = null
    private var approval: CompletableDeferred<Boolean>? = null
    fun confirm(accepted: Boolean) { approval?.complete(accepted) }
    fun cancel() { if (state.value.phase != "committing") job?.cancel() }
    fun clear() { generation++; job?.cancel(); mutable.value = AppInstallationState() }
    fun add(apk: TargetApkInfo) {
        if(state.value.busy) return
        val selected = state.value.selectedApks
        val error = when {
            selected.any { it.root == apk.root && it.path == apk.path } -> "此 APK 已加入"
            selected.size >= 32 -> "最多选择 32 个 APK"
            selected.any { it.root != apk.root } -> "多包必须位于同一文件范围"
            else -> null
        }
        mutable.update { if(error != null) it.copy(error=error) else it.copy(selectedApks=selected+apk,error=null) }
    }
    fun remove(apk: TargetApkInfo) { if(!state.value.busy) mutable.update { it.copy(selectedApks=it.selectedApks.filterNot { entry -> entry.root==apk.root && entry.path==apk.path },error=null) } }
    fun prepare(apk: TargetApkInfo, update: Boolean, available: Boolean) = prepareApks(listOf(apk), update, available)
    fun prepareSelected(update: Boolean, available: Boolean) = prepareApks(state.value.selectedApks,update,available)
    private fun prepareApks(apks: List<TargetApkInfo>, update: Boolean, available: Boolean) = run(available) { token, target ->
        validateApkSet(apks)
        val apk = apks.first()
        var taskId: String? = null
        var finished = false
        var prepared: AppInstallProposal? = null
        try {
            var task = json.decodeFromString<TransferProgress>(runtime.requestAt(target, "apps.install", buildJsonObject {
                put("root", apk.root); put("update", update)
                if(apks.size == 1) { put("path",apk.path); put("sha256",apk.sha256) }
                else putJsonArray("apks") { apks.forEach { entry -> add(buildJsonObject { put("path",entry.path); put("sha256",entry.sha256) }) } }
            }))
            taskId = task.id
            while (true) {
                runtime.checkTransferTarget(target)
                check(task.id == taskId && task.direction == "apk_prepare" && task.root == apk.root && task.name == apk.path) { "APK 准备对象不匹配" }
                if (token == generation) mutable.update { it.copy(phase = task.phase, bytes = task.bytesTransferred, total = task.sizeBytes) }
                if (task.phase in listOf("completed", "failed", "cancelled")) break
                delay(300); task = json.decodeFromString(runtime.requestAt(target, "task.progress", buildJsonObject { put("id", taskId) }))
            }
            check(task.phase == "completed") { task.error ?: "APK 准备未完成" }
            prepared = json.decodeFromJsonElement<AppInstallProposal>(checkNotNull(task.result))
            val proposed = prepared.apks.ifEmpty { listOf(prepared.apk) }
            check(proposed.toSet() == apks.toSet() && prepared.update == update) { "APK 确认内容与所选文件不匹配" }
            executeProposal(prepared, token, target); finished = true
        } finally {
            if (!finished) withContext(NonCancellable) { withTimeoutOrNull(5000) { runCatching {
                runtime.checkTransferTarget(target)
                prepared?.let { runtime.approveInstallAt(target, buildJsonObject { put("id", it.id); put("accepted", false) }) }
                if (prepared == null && taskId != null) runtime.requestAt(target, "task.cancel", buildJsonObject { put("id", taskId) })
            } } }
        }
    }
    fun pending(available: Boolean) = run(available) { token, target ->
        val proposals = json.decodeFromString<List<AppInstallProposal>>(runtime.requestAt(target, "apps.install_pending", JsonObject(emptyMap())))
        executeProposal(proposals.firstOrNull() ?: error("没有待确认的 APK 安装"), token, target)
    }
    private fun run(available: Boolean, operation: suspend (Long, RuntimeTransferTarget) -> Unit) {
        if (!available || state.value.busy) return
        val token = generation
        mutable.update { it.copy(busy = true, phase = "preparing", error=null, message=null) }
        job = scope.launch {
            try { operation(token, runtime.transferTarget()) }
            catch (e: CancellationException) { if (token == generation) mutable.update { it.copy(phase = "cancelled") }; throw e }
            catch (e: Exception) { if (token == generation) mutable.update { it.copy(phase = "failed", error = e.message ?: "安装操作失败") } }
            finally { if (token == generation) mutable.update { it.copy(busy = false, proposal = null) } }
        }
    }
    private suspend fun executeProposal(proposal: AppInstallProposal, token: Long, target: RuntimeTransferTarget) {
        check(proposal.kind == "app_install" && proposal.approvalRequired && (proposal.currentApp == null || proposal.update)) { "安装提案无效" }
        val apks = proposal.apks.ifEmpty { listOf(proposal.apk) }
        validateApkSet(apks)
        check(apks.single { it.split.isNullOrEmpty() } == proposal.apk) { "base APK 确认内容不匹配" }
        val pending = CompletableDeferred<Boolean>(); approval = pending
        var submitted = false
        try {
            runtime.checkTransferTarget(target)
            if (token == generation) mutable.update { it.copy(proposal = proposal, phase = "awaiting_approval") }
            if (!pending.await()) { if (token == generation) mutable.update { it.copy(phase = "cancelled") }; return }
            runtime.checkTransferTarget(target); submitted = true
            var task = json.decodeFromString<TransferProgress>(runtime.approveInstallAt(target, buildJsonObject { put("id", proposal.id) }))
            if (token == generation) mutable.update { it.copy(proposal = null, phase = "committing") }
            val id = task.id
            while (true) {
                check(task.id == id && task.direction == "app_install" && task.root == "apps" && task.name == proposal.apk.packageName && task.unit == "bytes") { "安装任务对象不匹配" }
                if(task.phase in listOf("completed", "failed", "cancelled")) break
                delay(300); runtime.checkTransferTarget(target)
                task = json.decodeFromString(runtime.requestAt(target, "task.progress", buildJsonObject { put("id", id) }))
            }
            check(task.phase == "completed") { task.error ?: "安装未完成；请查询应用信息" }
            val result = checkNotNull(task.result).jsonObject
            check(result["installed"]?.jsonPrimitive?.boolean == true && result["package"]?.jsonPrimitive?.content == proposal.apk.packageName && result["versionCode"]?.jsonPrimitive?.long == proposal.apk.versionCode && result["userId"]?.jsonPrimitive?.int == proposal.userId && result["sha256"]?.jsonPrimitive?.content == proposal.apk.sha256) { "安装结果对象不匹配，请查询应用信息" }
            if(apks.size > 1) {
                val installedApks = json.decodeFromJsonElement<List<TargetApkInfo>>(checkNotNull(result["apks"]))
                check(installedApks.toSet() == apks.toSet()) { "多包安装结果不匹配，请查询应用信息" }
            }
            if (token == generation) mutable.update { it.copy(phase = "completed", message = "安装完成：${proposal.apk.packageName}（版本 ${proposal.apk.versionCode}，用户 ${proposal.userId}）") }
        } finally {
            if (approval === pending) approval = null
            if (!submitted) withContext(NonCancellable) { withTimeoutOrNull(5000) { runCatching {
                runtime.checkTransferTarget(target); runtime.approveInstallAt(target, buildJsonObject { put("id", proposal.id); put("accepted", false) })
            } } }
        }
    }
}
