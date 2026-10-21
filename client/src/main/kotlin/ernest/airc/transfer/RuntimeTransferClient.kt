package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class RuntimeTransferTarget(val host: String, val port: Int, val generation: Int)

/** Large transfers use 64 KiB buffers. Caller owns the returned verified temporary download. */
class RuntimeTransferClient(private val runtime: RuntimeConnection) : AutoCloseable {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val uploadInputs=java.util.concurrent.ConcurrentHashMap.newKeySet<UploadInputGuard>()
    private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
    private suspend fun command(target: RuntimeTransferTarget, tool: String, args: JsonObject): TransferProgress {
        runtime.checkTransferTarget(target)
        val result = json.decodeFromString<TransferProgress>(runtime.requestAt(target, tool, args))
        runtime.checkTransferTarget(target)
        check(result.id.matches(Regex("[a-f0-9-]{36}")) && result.dataPath == "/transfers/${result.id}/data" && result.sizeBytes >= 0) { "无效传输任务描述" }
        check(tool!="task.progress" || result.id==args["id"]?.jsonPrimitive?.content) { "传输任务 ID 不匹配" }
        return result
    }
    private suspend fun cancel(target: RuntimeTransferTarget, id: String) = withContext(NonCancellable) {
        withTimeoutOrNull(5000) {
            runCatching { execute(client.newCall(Request.Builder().url(RuntimeConnection.url(target.host,target.port,"/transfers/$id")).delete().build())) { } }
        }
    }
    suspend fun download(path: String, temporaryDirectory: File, root: String = "downloads", onProgress: (TransferProgress) -> Unit = {}): Pair<String,File> {
        val target = runtime.transferTarget()
        var task: TransferProgress? = null
        var temporary: File? = null
        try {
            var current = command(target,"task.start",buildJsonObject { put("root",root);put("direction","download");put("path",path) })
            task = current; onProgress(current)
            while (current.phase == "hashing") {
                delay(500)
                current = command(target,"task.progress",buildJsonObject { put("id",current.id) }); onProgress(current)
            }
            check(current.phase == "ready" && current.sha256?.matches(Regex("[a-f0-9]{64}")) == true) { current.error ?: "下载任务尚未就绪" }
            check(validTargetFileName(current.name)) { "无效目标文件名" }
            check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs()) { "无法创建下载缓存目录" }
            val output = File.createTempFile("airc-download-", ".part", temporaryDirectory); temporary = output
            val expected = current
            execute(client.newCall(Request.Builder().url(RuntimeConnection.url(target.host,target.port,current.dataPath)).build())) { response ->
                check(response.isSuccessful) { "流式下载失败 HTTP ${response.code}" }
                val body = checkNotNull(response.body)
                check(body.contentType()?.toString() == "application/octet-stream" && body.contentLength()==expected.sizeBytes) { "文件响应类型或长度不匹配" }
                val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
                body.byteStream().use { input -> output.outputStream().use { sink ->
                    val buffer = ByteArray(64*1024)
                    while(true) {
                        runtime.checkTransferTarget(target)
                        val read = input.read(buffer); if(read<0) break
                        count += read; check(count<=expected.sizeBytes) { "下载数据超出声明长度" }
                        sink.write(buffer,0,read);digest.update(buffer,0,read)
                        onProgress(expected.copy(phase="downloading",bytesTransferred=count))
                    }
                } }
                check(count==expected.sizeBytes && digest.digest().hex()==expected.sha256) { "文件长度或 SHA-256 校验失败" }
            }
            runtime.checkTransferTarget(target)
            onProgress(current.copy(phase="completed",bytesTransferred=current.sizeBytes))
            return current.name to output.also { temporary = null }
        } catch(error: Exception) {
            task?.let { cancel(target,it.id) }
            currentCoroutineContext().ensureActive()
            throw error
        } finally { temporary?.delete() }
    }
    suspend fun upload(path: String, size: Long, sha256: String, source: () -> InputStream, root: String = "inbox", overwriteToken: String? = null, onProgress: (TransferProgress) -> Unit = {}): TransferProgress {
        return uploadAt(runtime.transferTarget(),path,size,sha256,source,root,overwriteToken,onProgress)
    }
    internal suspend fun uploadAt(target: RuntimeTransferTarget, path:String,size:Long,sha256:String,source:()->InputStream,root:String="inbox",overwriteToken:String?=null,onProgress:(TransferProgress)->Unit={}):TransferProgress {
        check(!closed.get()){ "传输客户端已关闭" }
        runtime.checkTransferTarget(target);var task: TransferProgress? = null
        val guard=UploadInputGuard();uploadInputs.add(guard)
        if(closed.get())guard.close()
        try {
            val current = command(target,"task.start",buildJsonObject { put("root",root);put("direction","upload");put("path",path);put("sizeBytes",size);put("sha256",sha256);overwriteToken?.let { put("overwriteToken",it) } })
            task=current; check(current.phase=="awaiting_upload") { current.error ?: "上传任务未就绪" }; onProgress(current)
            val body=object:RequestBody() {
                override fun contentType(): MediaType = "application/octet-stream".toMediaType()
                override fun contentLength(): Long = size
                override fun writeTo(sink: BufferedSink) {
                    val input=guard.open(source)
                    try { val buffer=ByteArray(64*1024);var count=0L
                        while(true) { runtime.checkTransferTarget(target);val read=input.read(buffer);if(read<0)break;count+=read;check(count<=size){"源文件大小变化"};sink.write(buffer,0,read);onProgress(current.copy(phase="uploading",bytesTransferred=count)) }
                        check(count==size){"源文件大小变化"}
                    } finally { guard.release(input) }
                }
            }
            val result=coroutineScope {
                val inputMonitor=launch(Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED){try{awaitCancellation()}finally{guard.close()}}
                try { execute(client.newCall(Request.Builder().url(RuntimeConnection.url(target.host,target.port,current.dataPath)).put(body).build())) { response ->
                val responseSource=checkNotNull(response.body).source()
                responseSource.request(16385)
                check(responseSource.buffer.size <= 16384) { "传输结果超过大小限制" }
                val payload=responseSource.buffer.readUtf8()
                val result=runCatching { json.decodeFromString<TransferProgress>(payload) }.getOrNull()
                check(response.isSuccessful) { "上传失败 HTTP ${response.code}: ${result?.error?.take(160) ?: "请检查服务日志"}" }
                checkNotNull(result) { "无效传输结果" }
                check(result.phase=="completed" && result.id==current.id && result.sha256==sha256 && result.bytesTransferred==size) { "上传结果校验失败" }
                result
            } } finally { inputMonitor.cancel() }
            }
            runtime.checkTransferTarget(target);onProgress(result);return result
        } catch(error: Exception) {
            task?.let { cancel(target,it.id) }
            // A nested I/O scope may finish with its source-close IOException while
            // cancellation is propagating. Preserve cancellation at the caller boundary.
            currentCoroutineContext().ensureActive()
            throw error
        }
        finally { guard.close();uploadInputs.remove(guard) }
    }
    override fun close() { closed.set(true);uploadInputs.forEach{it.close()};client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
}
internal fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
