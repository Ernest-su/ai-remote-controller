package ernest.airc

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Downloads only server-issued PNG resources from the explicitly selected target. */
class RuntimeArtifactClient : AutoCloseable {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun download(host: String, port: Int, artifact: BinaryArtifact): ByteArray {
        require(artifact.id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) { "无效资源 ID" }
        require(artifact.path == "/artifacts/${artifact.id}") { "无效资源路径" }
        require(artifact.mimeType == "image/png" && artifact.sizeBytes in 33..MAX_BYTES) { "无效截图类型或大小" }
        require(artifact.width in 1..16384 && artifact.height in 1..16384 && artifact.width.toLong() * artifact.height <= 32L * 1024 * 1024) { "无效截图尺寸" }
        return execute(client.newCall(Request.Builder().url(RuntimeConnection.url(host, port, artifact.path)).build())) { response ->
            check(response.isSuccessful) { "截图下载失败 HTTP ${response.code}，请重新截图" }
            val body = checkNotNull(response.body)
            check(body.contentType()?.toString() == "image/png") { "截图响应类型不匹配" }
            check(body.contentLength() == -1L || body.contentLength() == artifact.sizeBytes) { "截图响应长度不匹配" }
            val source = body.source()
            source.request(artifact.sizeBytes + 1)
            val bytes = source.buffer.readByteArray()
            check(bytes.size.toLong() == artifact.sizeBytes) { "截图数据长度不匹配" }
            check(bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) { "截图数据不是 PNG" }
            val header = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
            check(header.getInt(8) == 13 && header.getInt(12) == 0x49484452 && header.getInt(16) == artifact.width && header.getInt(20) == artifact.height) { "截图 PNG 尺寸不匹配" }
            bytes
        }
    }
    suspend fun downloadFile(host: String, port: Int, artifact: FileArtifact): ByteArray {
        require(artifact.id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) && artifact.path == "/artifacts/${artifact.id}") { "无效文件资源路径" }
        require(artifact.mimeType == "application/octet-stream" && artifact.sizeBytes in 0..MAX_BYTES) { "无效文件类型或大小" }
        require(validTargetFileName(artifact.name) && artifact.sha256.matches(Regex("[a-f0-9]{64}"))) { "无效文件名称或摘要" }
        return execute(client.newCall(Request.Builder().url(RuntimeConnection.url(host, port, artifact.path)).build())) { response ->
            check(response.isSuccessful) { "文件下载失败 HTTP ${response.code}，请重新请求" }
            val body = checkNotNull(response.body)
            check(body.contentType()?.toString() == artifact.mimeType) { "文件响应类型不匹配" }
            check(body.contentLength() == -1L || body.contentLength() == artifact.sizeBytes) { "文件响应长度不匹配" }
            val source = body.source(); source.request(artifact.sizeBytes + 1)
            val bytes = source.buffer.readByteArray()
            check(bytes.size.toLong() == artifact.sizeBytes) { "文件数据长度不匹配" }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            check(digest == artifact.sha256) { "文件 SHA-256 不匹配，请重新下载" }
            bytes
        }
    }
    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    companion object { const val MAX_BYTES = 16L * 1024 * 1024 }
}
