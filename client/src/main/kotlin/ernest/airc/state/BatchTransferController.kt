package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.concurrent.TimeUnit

/** Explicitly resumed, device-bound queue. No automatic action replay after reconnection. */
data class BatchFileExport(val name: String, val file: File)

class BatchTransferController(private val scope: CoroutineScope, private val runtime: RuntimeConnection, private val cache: File) : AutoCloseable {
    private val mutable = MutableStateFlow(BatchTransferState())
    val state = mutable.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().followRedirects(false).readTimeout(40, TimeUnit.SECONDS).writeTimeout(40, TimeUnit.SECONDS).build()
    private data class Item(val name: String, val path: String, val source: UploadSource? = null, var task: TransferProgress? = null, var file: File? = null)
    private var items = emptyList<Item>()
    private var device: String? = null
    private var root = "inbox"
    private var work: File? = null
    private var archive: File? = null
    private var job: Job? = null
    private var guard: UploadInputGuard? = null
    private var generation = 0
    @Volatile private var closed = false
    private val observer = scope.launch { runtime.session.collect { session ->
        if (state.value.running && session?.deviceId != device) pause()
        else if (state.value.running && session == null) pause()
    } }
    fun select(sources: List<UploadSource>) {
        if (state.value.running || state.value.paused || state.value.saving) return
        require(sources.size in 1..32 && sources.all { validTargetFileName(it.name) } && sources.map { it.name }.distinct().size == sources.size) { "最多32个文件，名称不能重复" }
        items = sources.map { Item(it.name, it.name, it) }
        mutable.value = BatchTransferState(names = items.map { it.name }, uploadSelection = true)
    }
    fun upload(root: String, directory: String) {
        if (state.value.running || state.value.paused || state.value.saving || items.isEmpty() || items.any { it.source == null }) return
        val session = runtime.session.value ?: return
        this.root = root; device = session.deviceId
        items = items.map { it.copy(path = listOf(directory, it.name).filter { p -> p.isNotEmpty() }.joinToString("/")) }
        begin(newTask = true)
    }
    fun download(root: String, directory: String, names: List<String>, mode: BatchDownloadMode = BatchDownloadMode.ZIP) {
        if (state.value.running || state.value.paused || state.value.saving || names.isEmpty() || names.size > 32 || names.distinct().size != names.size || names.any { !validTargetFileName(it) }) return
        if (names.any { !validBatchArchiveName(it) }) { mutable.update { it.copy(error = "ZIP 文件名不能包含反斜杠或冒号，请先重命名文件") }; return }
        val session = runtime.session.value ?: return
        cleanup(); this.root = root; device = session.deviceId
        mutable.update { it.copy(uploadSelection = false, downloadMode = mode, savedNames = emptyList(), readyToSave = false, error = null) }
        items = names.map { Item(it, listOf(directory, it).filter { p -> p.isNotEmpty() }.joinToString("/")) }
        begin(newTask = true)
    }
    fun resume() {
        if (!state.value.paused || state.value.running || job?.isActive == true) return
        if (runtime.session.value?.deviceId != device) { mutable.update { it.copy(error = "请先重新连接原被控设备再继续") }; return }
        begin()
    }
    fun pause() { if (state.value.running) { mutable.update { it.copy(paused = true, message = "已暂停，连接原设备后可继续") }; job?.cancel(); guard?.close() } }
    private fun begin(newTask: Boolean = false) {
        if (state.value.running && job?.isActive == true) return
        val target = runCatching { runtime.transferTarget() }.getOrNull() ?: return
        val token = ++generation
        mutable.update { it.copy(noticeId = if (newTask) java.util.UUID.randomUUID().toString() else it.noticeId, names = items.map { item -> item.name }, running = true, paused = false, error = null, readyToSave = false, target = device) }
        job = scope.launch {
            try {
                check(cache.isDirectory || cache.mkdirs())
                if (work == null) work = File(cache, "airc-batch-${java.util.UUID.randomUUID()}").apply { check(mkdir()) }
                for ((index,item) in items.withIndex()) {
                    ensureActive(); runtime.checkTransferTarget(target)
                    mutable.update { it.copy(index = index + 1, message = item.name) }
                    if (item.source != null) uploadItem(target,item) else downloadItem(target,item)
                }
                if (items.all { it.source == null } && state.value.downloadMode == BatchDownloadMode.ZIP) {
                    val zip = File(checkNotNull(work), "下载文件.zip")
                    withContext(Dispatchers.IO) { ZipOutputStream(zip.outputStream()).use { out -> items.forEach { item ->
                        ensureActive(); out.putNextEntry(ZipEntry(item.name)); checkNotNull(item.file).inputStream().use { it.copyTo(out,64*1024) }; out.closeEntry()
                    } } }
                    archive = zip
                }
                mutable.update { it.copy(message = "全部文件传输完成，摘要校验通过", paused = false, readyToSave = items.all { item -> item.source == null }) }
            } catch (e: CancellationException) { if (token == generation) mutable.update { it.copy(paused = true, message = "已暂停；原服务任务有效时可继续") }; throw e }
            catch (e: Exception) { if (token == generation) mutable.update { it.copy(paused = true, error = (e.message ?: "传输中断") + "。重新连接原设备后点继续；服务重启或任务过期请取消后重新选择。") } }
            finally { if (token == generation) mutable.update { it.copy(running = false) } }
        }
    }
    private suspend fun command(target: RuntimeTransferTarget, tool: String, args: JsonObject): TransferProgress =
        json.decodeFromString<TransferProgress>(runtime.requestAt(target,tool,args)).also { runtime.checkTransferTarget(target) }
    private suspend fun create(target: RuntimeTransferTarget, item: Item): TransferProgress {
        item.task?.let { old ->
            val fresh=command(target,"task.progress",buildJsonObject { put("id",old.id) })
            check(fresh.id == old.id && fresh.direction == old.direction && fresh.sizeBytes == old.sizeBytes && (old.sha256 == null || fresh.sha256 == old.sha256) && fresh.root == root && fresh.name == item.name) { "传输任务身份改变" }
            return fresh.also { item.task=it }
        }
        var size=0L; var digest: String?=null
        item.source?.let { source ->
            mutable.update { it.copy(message = "计算摘要：${item.name}", bytes = 0, total = source.size ?: 0) }
            digest = guardedSource(source) { input ->
                val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
                while(true) { currentCoroutineContext().ensureActive();runtime.checkTransferTarget(target);val n=input.read(buffer);if(n<0)break;size+=n;check(size<=1024L*1024*1024*1024);hash.update(buffer,0,n);mutable.update { it.copy(bytes=size) } }
                hash.digest().hex()
            }
        }
        val result=command(target,"task.start",buildJsonObject { put("direction",if(item.source==null) "download" else "upload");put("root",root);put("path",item.path);if(item.source!=null) {put("sizeBytes",size);put("sha256",digest!!)} })
        check(result.direction == if(item.source==null) "download" else "upload")
        check(result.id.matches(Regex("[a-f0-9-]{36}")) && result.dataPath == "/transfers/${result.id}/data" && result.name == item.name && result.root == root)
        if(item.source!=null) check(result.sha256==digest && result.sizeBytes==size)
        item.task=result
        return result
    }
    private suspend fun <T> guardedSource(source: UploadSource, action: suspend (InputStream) -> T): T = coroutineScope {
        val current=UploadInputGuard();guard=current
        val monitor=launch(Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()}finally {current.close()} }
        try { withContext(Dispatchers.IO) {val input=current.open(source.open);try {action(input)}finally {current.release(input)} } }
        finally {monitor.cancel();current.close();if(guard===current)guard=null}
    }
    private suspend fun uploadItem(target: RuntimeTransferTarget, item: Item) {
        var task=create(target,item)
        if(task.phase=="completed") return
        check(task.phase=="awaiting_upload") { task.error ?: "上传尚未就绪" }
        check(task.bytesTransferred in 0..task.sizeBytes)
        guardedSource(checkNotNull(item.source)) { input ->
            var skipped=0L;val buffer=ByteArray(64*1024)
            while(skipped<task.bytesTransferred) {currentCoroutineContext().ensureActive();val n=input.read(buffer,0,minOf(buffer.size.toLong(),task.bytesTransferred-skipped).toInt());check(n>0){"源文件缩短"};skipped+=n}
            var offset=task.bytesTransferred
            do {
                currentCoroutineContext().ensureActive();runtime.checkTransferTarget(target)
                val amount=minOf(4L*1024*1024,task.sizeBytes-offset)
                val start=offset;val expected=task
                val body=object:RequestBody() {
                    override fun contentType()="application/octet-stream".toMediaType()
                    override fun contentLength()=amount
                    override fun writeTo(sink:okio.BufferedSink) { var left=amount;while(left>0) {runtime.checkTransferTarget(target);val n=input.read(buffer,0,minOf(left,buffer.size.toLong()).toInt());check(n>0){"源文件缩短"};sink.write(buffer,0,n);left-=n} }
                }
                task=execute(http.newCall(Request.Builder().url(RuntimeConnection.url(target.host,target.port,"/transfers/${task.id}/range"))
                    .header("Range","bytes=$start-${(start+amount-1).coerceAtLeast(0)}").put(body).build())) { response ->
                    check(response.isSuccessful){"分块上传失败 HTTP ${response.code}"}
                    val payload=checkNotNull(response.body).source();payload.request(16385);check(payload.buffer.size<=16384)
                    json.decodeFromString<TransferProgress>(payload.buffer.readUtf8())
                }
                check(task.id==expected.id && task.sha256==expected.sha256 && task.bytesTransferred==start+amount && task.root==root && task.sizeBytes==expected.sizeBytes){"分块上传回执不匹配"}
                item.task=task;offset=task.bytesTransferred;mutable.update {it.copy(bytes=offset,total=task.sizeBytes,message=item.name)}
            } while(offset<task.sizeBytes)
            check(task.phase=="completed"){task.error ?: "上传未完成"}
        }
    }
    private suspend fun downloadItem(target: RuntimeTransferTarget, item: Item) {
        var task=create(target,item)
        while(task.phase=="hashing") {delay(300);val old=task;task=command(target,"task.progress",buildJsonObject {put("id",old.id)});check(task.id==old.id);item.task=task}
        check(task.phase in listOf("ready","completed") && task.sha256?.matches(Regex("[a-f0-9]{64}"))==true){task.error ?: "下载未就绪"}
        val file=item.file ?: File(checkNotNull(work),"${java.util.UUID.randomUUID()}.part").also {check(it.createNewFile());item.file=it}
        withContext(Dispatchers.IO) {
            var offset=file.length();check(offset<=task.sizeBytes)
            while(offset<task.sizeBytes) {
                currentCoroutineContext().ensureActive();runtime.checkTransferTarget(target)
                val end=minOf(task.sizeBytes-1,offset+4*1024*1024-1);val start=offset
                val context=currentCoroutineContext()
                try {
                    execute(http.newCall(Request.Builder().url(RuntimeConnection.url(target.host,target.port,"/transfers/${task.id}/range")).header("Range","bytes=$start-$end").build())) { response ->
                        check(response.code==206 && response.header("Content-Range")=="bytes $start-$end/${task.sizeBytes}"){"分块下载范围不匹配"}
                        val body=checkNotNull(response.body);check(body.contentLength()==end-start+1)
                        var count=0L
                        body.byteStream().use { input -> java.io.FileOutputStream(file,true).use { sink ->
                            val buffer=ByteArray(64*1024)
                            while(true) { context.ensureActive();runtime.checkTransferTarget(target);val n=input.read(buffer);if(n<0)break;count+=n;check(count<=end-start+1);sink.write(buffer,0,n) }
                            sink.fd.sync()
                        } }
                        check(count==end-start+1) { "下载分块不完整" }
                    }
                } catch(e:Exception) {java.io.RandomAccessFile(file,"rw").use {it.setLength(start)};throw e}
                offset=end+1;mutable.update {it.copy(bytes=offset,total=task.sizeBytes,message=item.name)}
            }
            val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use { input ->val buf=ByteArray(64*1024);while(true){currentCoroutineContext().ensureActive();val n=input.read(buf);if(n<0)break;digest.update(buf,0,n)} }
            check(file.length()==task.sizeBytes && digest.digest().hex()==task.sha256){"下载摘要不匹配，请取消后重新下载"}
        }
    }
    fun exportFile(): File? = archive
    fun beginFileExport(expectedNoticeId: String?): List<BatchFileExport> {
        val current = state.value
        if (closed || !current.readyToSave || current.running || current.saving || current.downloadMode != BatchDownloadMode.INDIVIDUAL || current.noticeId != expectedNoticeId) return emptyList()
        val pending = items.filter { it.name !in current.savedNames }.map { BatchFileExport(it.name, checkNotNull(it.file)) }
        if (pending.isNotEmpty()) mutable.update { it.copy(saving = true, error = null) }
        return pending
    }
    fun fileSaved(expectedNoticeId: String?, name: String) {
        mutable.update { if (it.noticeId == expectedNoticeId && it.saving && name in it.names) it.copy(savedNames = (it.savedNames + name).distinct()) else it }
    }
    fun finishFileExport(expectedNoticeId: String?, error: String? = null) {
        mutable.update { if (it.noticeId == expectedNoticeId) it.copy(saving = false, error = error) else it }
        if (closed) cleanup()
    }
    fun cancel() {
        if (state.value.saving) return
        if (state.value.message == "正在取消") return
        val previous=job
        if(previous?.isActive == true) { previous.cancel();guard?.close() }
        val target=runCatching {runtime.transferTarget()}.getOrNull()
        val originalDevice=device;val ids=items.mapNotNull {it.task?.id}
        mutable.update {it.copy(running=true,message="正在取消")}
        job=scope.launch {
            previous?.join()
            if(target!=null && runtime.session.value?.deviceId==originalDevice) ids.forEach {id->runCatching {runtime.requestAt(target,"task.cancel",buildJsonObject {put("id",id)})}}
            cleanup();items=emptyList();device=null;mutable.value=BatchTransferState()
        }
    }
    private fun cleanup() {work?.deleteRecursively();work=null;archive=null}
    override fun close() {
        closed = true
        observer.cancel()
        val previous=job
        previous?.cancel();guard?.close()
        if (!state.value.saving) { if(previous==null || previous.isCompleted)cleanup() else previous.invokeOnCompletion {cleanup()} }
        http.dispatcher.cancelAll();http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()
    }
}
