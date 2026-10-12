package ernest.airc

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class RuntimePreviewClient : AutoCloseable {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5,TimeUnit.SECONDS).readTimeout(40,TimeUnit.SECONDS).build()
    suspend fun read(host: String, port: Int, descriptor: ScreenPreviewDescriptor, onFrame: (ScreenPreviewFrame) -> Unit) {
        ScreenPreviewReader.validate(descriptor)
        execute(client.newCall(Request.Builder().url(RuntimeConnection.url(host,port,descriptor.path)).build())) { response ->
            check(response.isSuccessful) { "预览下载失败 HTTP ${response.code}" }
            val body = checkNotNull(response.body)
            check(body.contentType()?.toString() == descriptor.mimeType) { "预览响应类型不匹配" }
            ScreenPreviewReader.read(body.byteStream(), descriptor, onFrame)
        }
    }
    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}
