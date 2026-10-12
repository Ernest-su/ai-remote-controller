package ernest.airc

import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Response
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Release signatures establish origin; hashes protect cached/transferred assets. */
class RuntimeReleaseRepository(
    private val cache: File,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(false).build(),
    private val signatureVerifier: ReleaseSignatureVerifier = ReleaseTrust.verifier(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val downloadLock = Mutex()
    companion object {
        const val GITHUB_REPOSITORY = "https://github.com/Ernest-su/ai-remote-controller"

        fun githubManifest(repository: String = GITHUB_REPOSITORY): HttpUrl {
            val url = address(repository.trim())
            val parts = url.pathSegments.filter { it.isNotEmpty() }
            require(url.host == "github.com" && url.port == 443 && url.query == null && parts.size == 2) {
                "请输入 GitHub 仓库地址，例如 https://github.com/owner/repository"
            }
            val owner = parts[0]
            val repo = parts[1].removeSuffix(".git")
            require(listOf(owner, repo).all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")) }) { "无效的 GitHub 仓库名称" }
            return url.newBuilder().encodedPath("/$owner/$repo/releases/latest/download/manifest.json").build()
        }

        fun address(value: String): HttpUrl = value.toHttpUrl().also {
            require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null) { "发布地址必须是无凭据的 HTTPS 地址" }
        }
        fun verify(input: InputStream, asset: RuntimeAsset): Boolean {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > asset.size) return false
                digest.update(buffer, 0, count)
            }
            return size == asset.size && digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256
        }
    }
    suspend fun release(url: String, appVersionCode: Int): RuntimeRelease {
        val source = address(url)
        val bytes = download(source, 256 * 1024)
        val signatureUrl = source.newBuilder().encodedPath(source.encodedPath + ".sig").build()
        val signature = download(signatureUrl, 4096)
        signatureVerifier.verify(bytes, signature)
        return json.decodeFromString<RuntimeRelease>(bytes.toString(Charsets.UTF_8)).also { manifest ->
            manifest.validate(appVersionCode, online = true)
            manifest.assets.forEach { address(it.url!!) }
        }
    }
    private suspend fun download(url: HttpUrl, limit: Int): ByteArray =
        execute(client.newCall(Request.Builder().url(url).build())) { response ->
            check(response.isSuccessful) { "发布查询失败 HTTP ${response.code}" }
            response.body!!.byteStream().use { it.readBytesLimited(limit) }
        }
    suspend fun asset(asset: RuntimeAsset, report: (String) -> Unit): File = withContext(Dispatchers.IO) { downloadLock.withLock {
        val operation = currentCoroutineContext()
        require(asset.sha256.matches(Regex("[a-f0-9]{64}")) && asset.size in 1..128L * 1024 * 1024) { "无效的资产摘要或大小" }
        check(cache.isDirectory || cache.mkdirs()) { "无法创建下载缓存" }
        val destination = File(cache, asset.sha256)
        if (destination.isFile && destination.inputStream().use { verify(it, asset) }) return@withLock destination
        destination.delete()
        val temporary = File(cache, "${UUID.randomUUID()}.part")
        try {
            execute(client.newCall(Request.Builder().url(address(asset.url ?: error("资产缺少下载地址"))).build())) { response ->
                check(response.isSuccessful) { "资产下载失败 HTTP ${response.code}" }
                val length = response.body!!.contentLength()
                check(length == -1L || length == asset.size) { "下载大小与发布清单不符" }
                response.body!!.byteStream().use { input -> temporary.outputStream().use { output ->
                    var transferred = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        operation.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        transferred += count
                        check(transferred <= asset.size) { "下载超过发布声明大小" }
                        output.write(buffer, 0, count)
                        report("下载 ${transferred * 100 / asset.size}%")
                    }
                } }
            }
            check(temporary.inputStream().use { verify(it, asset) }) { "下载的二进制摘要校验失败" }
            operation.ensureActive()
            check(temporary.renameTo(destination)) { "无法保存下载缓存" }
            destination
        } finally { temporary.delete() }
    } }
}

/** Cancellation must close the call even while execute()/read() is blocked. */

private fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        check(output.size() + count <= limit) { "发布清单过大" }
        output.write(buffer, 0, count)
    }
}
