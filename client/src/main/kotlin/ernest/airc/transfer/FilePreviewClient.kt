package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

/** Short-lived, read-only range streams. No file contents or URLs are persisted. */
class FilePreviewClient(private val runtime: RuntimeConnection) : AutoCloseable {
    private val thumbnailGate = Semaphore(2)
    suspend fun <T> withThumbnailPermit(block: suspend () -> T): T = thumbnailGate.withPermit {
        currentCoroutineContext().ensureActive()
        block()
    }
    val session = runtime.session
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    inner class Resource internal constructor(val descriptor: FilePreviewResource, private val target: RuntimeTransferTarget) {
        val url: String get() { runtime.checkTransferTarget(target); return RuntimeConnection.url(target.host, target.port, descriptor.dataPath).toString() }
        fun checkCurrent() = runtime.checkTransferTarget(target)
        suspend fun read(maxBytes: Int, prefix: Boolean = false): ByteArray {
            require(maxBytes in 1..16*1024*1024)
            checkCurrent()
            val size = descriptor.sizeBytes
            check(prefix || size <= maxBytes) { "图片超过 16 MiB 预览上限，请下载后查看。" }
            if (size == 0L) return ByteArray(0)
            val length = minOf(size, maxBytes.toLong()).toInt()
            return execute(http.newCall(Request.Builder().url(url).header("Range", "bytes=0-${length-1}").build())) { response ->
                checkCurrent()
                check(response.code == 206 && response.header("Content-Range") == "bytes 0-${length-1}/$size") { "预览范围响应不匹配，请重新打开。" }
                val body = checkNotNull(response.body)
                check(body.contentLength() == length.toLong()) { "预览长度不匹配" }
                val source = body.source()
                source.request(length.toLong() + 1)
                check(source.buffer.size == length.toLong()) { "预览数据长度不匹配" }
                source.buffer.readByteArray().also { checkCurrent() }
            }
        }
        suspend fun release() = withContext(NonCancellable) {
            withTimeoutOrNull(3000) { runCatching { runtime.requestAt(target, "files.preview_close", buildJsonObject { put("id", descriptor.id) }) } }
            Unit
        }
    }

    suspend fun apkIcon(root: String, path: String): ByteArray = runtime.apkIcon(root,path)

    suspend fun open(root: String, path: String): Resource {
        val target = runtime.transferTarget()
        // Preserve the descriptor until received so cancellation can revoke a newly created resource.
        val descriptor = withContext(NonCancellable) {
            json.decodeFromString<FilePreviewResource>(runtime.requestAt(target, "files.preview", buildJsonObject { put("root",root); put("path",path) }))
        }
        check(descriptor.id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) &&
            descriptor.dataPath == "/media/${descriptor.id}") { "预览资源地址无效" }
        val resource = Resource(descriptor, target)
        try {
            currentCoroutineContext().ensureActive()
            runtime.checkTransferTarget(target)
            check(descriptor.root == root && descriptor.path == path && descriptor.sizeBytes in 0..1024L*1024*1024*1024) { "预览目标响应不匹配" }
            return resource
        } catch (error: Exception) { resource.release(); throw error }
    }

    override fun close() { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}

data class FilePreviewText(val text: String, val encoding: String)

/** BOM-aware text only; HTML and source files are displayed literally. */
fun decodeFilePreviewText(bytes: ByteArray): FilePreviewText {
    val (charset, offset) = when {
        bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> Charsets.UTF_8 to 3
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE to 2
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE to 2
        else -> Charsets.UTF_8 to 0
    }
    // A bounded prefix may cut the final code point. Ignore only the incomplete final sequence.
    fun decode(cs: Charset): String {
        val decoder = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, offset, bytes.size-offset)
        val output = java.nio.CharBuffer.allocate(bytes.size + 1)
        val result = decoder.decode(input, output, false)
        if (result.isError) result.throwException()
        output.flip()
        return output.toString()
    }
    val decoded = try { decode(charset) to charset.name() }
        catch (_: java.nio.charset.CharacterCodingException) {
            check(offset == 0) { "无法解码此文本文件" }
            decode(Charset.forName("GB18030")) to "GB18030"
        }
    check(decoded.first.none { it == '\u0000' } && decoded.first.count { it.isISOControl() && it !in "\n\r\t" } <= decoded.first.length / 50) { "文件包含二进制内容，无法作为文本预览。" }
    return FilePreviewText(decoded.first, decoded.second)
}
