package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class FileExport internal constructor(val name: String, val file: File, internal val generation: Long)

data class UploadSource(val name: String, val size: Long?, val open: () -> InputStream)

class FileTransferController(private val scope: CoroutineScope, private val runtime: RuntimeConnection) : AutoCloseable {
    private val client = RuntimeTransferClient(runtime)
    private val mutable = MutableStateFlow(FileTransferState())
    val state = mutable.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private var approval: CompletableDeferred<Boolean>? = null
    fun confirm(accepted: Boolean) { approval?.complete(accepted) }
    private suspend fun approve(target: RuntimeTransferTarget, token: Long, kind: String, root: String, path: String, recursive: Boolean = false, expectedSha256: String? = null): String {
        val result = runtime.requestAt(target,if(kind=="delete") "files.delete" else "files.prepare_overwrite",buildJsonObject { put("root",root);put("path",path);if(kind=="delete")put("recursive",recursive);expectedSha256?.let { put("expectedSha256",it) } })
        val proposal = json.decodeFromString<FileActionProposal>(result)
        check(proposal.kind==kind && proposal.root==root && proposal.path==path && proposal.approvalRequired &&
            (kind!="delete" || proposal.recursive==recursive)) { "操作确认信息不匹配" }
        return approveProposal(target,token,proposal)
    }
    private suspend fun approveProposal(target: RuntimeTransferTarget, token: Long, proposal: FileActionProposal): String {
        runtime.checkTransferTarget(target)
        val pending=CompletableDeferred<Boolean>();approval=pending
        update(token) { it.copy(phase="awaiting_approval",proposal=proposal,root=proposal.root,path=proposal.path) }
        var submitted=false
        try {
            if(!pending.await()) throw CancellationException("已取消操作")
            runtime.checkTransferTarget(target)
            submitted=true
            return runtime.approveFileAt(target,buildJsonObject { put("id",proposal.id) })
        } finally {
            if(!submitted) withContext(NonCancellable) { withTimeoutOrNull(5000) { runCatching { runtime.checkTransferTarget(target);runtime.approveFileAt(target,buildJsonObject { put("id",proposal.id);put("accepted",false) }) } } }
            if(approval===pending)approval=null
            update(token) { it.copy(proposal=null) }
        }
    }
    fun reviewPending(available: Boolean) {
        if(!available) return
        start("downloads","", "delete") { token ->
            val target=runtime.transferTarget()
            val proposals=json.decodeFromString<List<FileActionProposal>>(runtime.requestAt(target,"files.pending",JsonObject(emptyMap())))
            val proposal=proposals.firstOrNull { it.kind=="delete" } ?: error("没有待确认的删除操作")
            runDeletion(target,token,approveProposal(target,token,proposal))
        }
    }
    private var source: UploadSource? = null
    private var job: Job? = null
    @Volatile private var generation = 0L
    private var downloaded: Pair<String,File>? = null
    private var export: Pair<Long,Pair<String,File>>? = null
    fun select(source: UploadSource) { if (!state.value.running) { this.source=source; mutable.update { it.copy(operation="upload",sourceName=source.name,sourceSize=source.size,error=null,phase="idle",bytes=0,total=null,readyToSave=false,proposal=null) } } }
    private fun update(token: Long, action: (FileTransferState)->FileTransferState) { mutable.update { if (generation==token) action(it) else it } }
    private fun progress(token: Long, value: TransferProgress) = update(token) { it.copy(phase=value.phase,bytes=value.bytesTransferred,total=if(value.unit=="items") null else value.sizeBytes,name=value.name,root=value.root,unit=value.unit) }
    private fun start(root: String, path: String, operation: String = "upload", action: suspend (Long)->Unit) {
        if (state.value.running) return
        val token = ++generation
        downloaded?.second?.delete();downloaded=null
        mutable.update { it.copy(noticeId=java.util.UUID.randomUUID().toString(),operation=operation,running=true,phase="preparing",bytes=0,total=null,path=path,root=root,error=null,readyToSave=false,proposal=null,unit="bytes") }
        job=scope.launch {
            try { action(token) }
            catch (e: CancellationException) { update(token) { it.copy(phase="cancelled") };throw e }
            catch (e: Exception) { update(token) { it.copy(phase="failed",error=e.message ?: "文件传输失败") } }
            finally { update(token) { it.copy(running=false) } }
        }
    }
    fun upload(root: String, path: String, available: Boolean, overwrite: Boolean = false) = uploadBound(root, path, available, overwrite, null, null)
    internal fun uploadEdited(root: String, path: String, bytes: ByteArray, expectedSha256: String, target: RuntimeTransferTarget) {
        runtime.checkTransferTarget(target)
        select(UploadSource(path.substringAfterLast('/'), bytes.size.toLong()) { bytes.inputStream() })
        uploadBound(root, path, true, true, expectedSha256, target)
    }
    private fun uploadBound(root: String, path: String, available: Boolean, overwrite: Boolean, expectedSha256: String?, boundTarget: RuntimeTransferTarget?) {
        val source=this.source ?: return
        if (!available || !validTargetFileName(path.substringAfterLast('/'))) return
        start(root,path) { token ->
            val target=boundTarget ?: runtime.transferTarget()
            runtime.checkTransferTarget(target)
            val sourceGuard=UploadInputGuard()
            val prepared=coroutineScope {
                val monitor=launch(Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { sourceGuard.close() } }
                try { withContext(Dispatchers.IO) {
                    val input=sourceGuard.open(source.open)
                    val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);var size=0L
                    try { val stream=input;while(true) { currentCoroutineContext().ensureActive();runtime.checkTransferTarget(target);val read=stream.read(buffer);if(read<0)break;size+=read;check(size<=1024L*1024*1024*1024){"文件超过 1 TiB 传输限额"};digest.update(buffer,0,read);update(token){it.copy(phase="hashing_source",bytes=size,total=source.size)} } } finally { sourceGuard.release(input) }
                    size to digest.digest().hex()
                } } catch(error:Exception) { currentCoroutineContext().ensureActive();throw error } finally { monitor.cancel();sourceGuard.close() }
            }
            runtime.checkTransferTarget(target)
            val overwriteToken=if(overwrite) { json.parseToJsonElement(approve(target,token,"overwrite",root,path,expectedSha256=expectedSha256)).jsonObject.getValue("id").jsonPrimitive.content } else null
            client.uploadAt(target,path,prepared.first,prepared.second,source.open,root,overwriteToken) { progress(token,it) }
        }
    }
    fun delete(root: String, path: String, recursive: Boolean, available: Boolean, onComplete: () -> Unit = {}) {
        if (!available || path.isEmpty()) return
        start(root,path,"delete") { token ->
            val target=runtime.transferTarget()
            try { runDeletion(target,token,approve(target,token,"delete",root,path,recursive)) }
            finally { if(token==generation)onComplete() }
        }
    }
    private suspend fun runDeletion(target: RuntimeTransferTarget, token: Long, response: String) {
        runtime.checkTransferTarget(target)
        var task=json.decodeFromString<TransferProgress>(response)
        check(task.direction=="delete") { "删除任务响应不匹配" }
        val taskId=task.id
        val taskRoot=task.root
        try {
            while(true) {
                runtime.checkTransferTarget(target)
                progress(token,task)
                if(task.phase in listOf("completed","failed","cancelled")) break
                delay(300)
                val next=json.decodeFromString<TransferProgress>(runtime.requestAt(target,"task.progress",buildJsonObject { put("id",taskId) }))
                check(next.id==taskId && next.direction=="delete" && next.root==taskRoot) { "删除任务响应不匹配" }
                task=next
            }
            check(task.phase=="completed") { task.error ?: "删除未完成" }
        } catch(e:Exception) {
            if(task.phase !in listOf("completed","failed","cancelled")) {
                withContext(NonCancellable) { withTimeoutOrNull(5000) { runCatching { runtime.checkTransferTarget(target);runtime.requestAt(target,"task.cancel",buildJsonObject { put("id",taskId) }) } } }
            }
            throw e
        }
    }
    fun download(root: String, path: String, cache: File, available: Boolean) {
        if (!available) return
        start(root,path,"download") { token ->
            val result=client.download(path,cache,root) { progress(token,it) }
            if (token==generation) { downloaded=result;update(token){it.copy(readyToSave=true)} } else result.second.delete()
        }
    }
    fun prepareExport(): String? {
        val result=downloaded ?: return null
        export?.second?.second?.delete();export=generation to result;downloaded=null;mutable.update { it.copy(readyToSave=false) }
        return result.first
    }
    fun takeExport(): FileExport? = export?.let { FileExport(it.second.first,it.second.second,it.first) }.also { export=null }
    fun restoreExport(value: FileExport) {
        if (value.generation==generation && downloaded==null) { downloaded=value.name to value.file;mutable.update{it.copy(readyToSave=true)} }
        else value.file.delete()
    }
    fun cancel() { mutable.update { if(it.running)it.copy(phase="cancelling")else it };job?.cancel() }
    fun clear() { generation++;job?.cancel();downloaded?.second?.delete();downloaded=null;export?.second?.second?.delete();export=null;mutable.value=FileTransferState(sourceName=source?.name,sourceSize=source?.size) }
    override fun close() { clear();export?.second?.second?.delete();export=null;client.close() }
}
