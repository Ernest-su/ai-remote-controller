package ernest.airc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable private data class CatalogResponse(val data: List<CatalogEntry>)
@Serializable private data class CatalogEntry(val id: String)

class ModelCatalogClient : ModelCatalog, AutoCloseable {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun close() { client.dispatcher.cancelAll(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }

    override suspend fun fetch(profile: ModelProfile): List<String> {
        val validated = profile.validated()
        return execute(client.newCall(Request.Builder().url("${validated.baseUrl}/models")
            .header("Authorization", "Bearer ${validated.apiKey}").build())) { response ->
            check(response.isSuccessful) { "模型列表请求失败 HTTP ${response.code}" }
            val body = response.body ?: error("模型列表响应为空")
            val source = body.source()
            source.request(1024 * 1024 + 1L)
            check(source.buffer.size <= 1024 * 1024) { "模型列表超过 1 MiB" }
            val entries = json.decodeFromString<CatalogResponse>(source.readUtf8()).data
            check(entries.size <= 2000) { "模型列表超过 2000 项" }
            check(entries.all { it.id.isNotBlank() && it.id.length <= 256 && it.id.none(Char::isISOControl) }) { "模型列表包含无效名称" }
            entries.map { it.id }.distinct().sorted()
        }
    }
}
