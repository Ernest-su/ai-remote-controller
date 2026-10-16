package ernest.airc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RuntimeConnection(private val language: () -> String = { java.util.Locale.getDefault().toLanguageTag() }) : AutoCloseable {
    private fun languageHeader() = if (java.util.Locale.forLanguageTag(language()).language == "zh") "zh" else "en"
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var socket: WebSocket? = null
    private val artifacts = RuntimeArtifactClient()
    @Volatile private var endpoint: Pair<String, Int>? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<RuntimeResponse>>()
    private val progressHandlers = ConcurrentHashMap<String, (RuntimeProgress) -> Unit>()
    private val stateLock = Any()
    private val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()
    private val mutableSession = MutableStateFlow<RuntimeSession?>(null)
    val session = mutableSession.asStateFlow()
    @Volatile private var currentGeneration = 0

    companion object {
        fun url(host: String, port: Int, path: String): HttpUrl = HttpUrl.Builder()
            .scheme("http").host(host).port(port).encodedPath(path).build()
    }

    suspend fun status(host: String, port: Int): RuntimeStatus =
        execute(client.newCall(Request.Builder().url(url(host, port, "/status")).header("Accept-Language", languageHeader()).build())) {
            check(it.isSuccessful) { "状态查询失败 HTTP ${it.code}" }
            json.decodeFromString<RuntimeStatus>(it.body!!.string()).also { status ->
                check(status.protocol == PROTOCOL_VERSION) { "不兼容的服务协议 ${status.protocol}" }
            }
        }

    suspend fun connect(host: String, port: Int): RuntimeStatus {
        val generation=synchronized(stateLock) { disconnect();currentGeneration }
        val status = status(host, port)
        val opened = CompletableDeferred<Unit>()
        synchronized(stateLock) {
        check(generation==currentGeneration) { "目标连接已改变" }
        endpoint = host to port
        socket = client.newWebSocket(Request.Builder().url(url(host, port, "/ws")).header("Accept-Language", languageHeader()).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(stateLock) {
                if (generation != currentGeneration) { webSocket.cancel(); return }
                mutableSession.value = RuntimeSession(status.deviceId,host,port,generation)
                mutableConnected.value = true
                opened.complete(Unit)
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != currentGeneration) return
                runCatching {
                    val message = json.parseToJsonElement(text).jsonObject
                    if (message["type"]?.jsonPrimitive?.content == "assistant.state") {
                        val progress = json.decodeFromString<RuntimeProgress>(text)
                        if (progress.protocol == PROTOCOL_VERSION) progressHandlers[progress.id]?.invoke(progress)
                    } else {
                        val response = json.decodeFromString<RuntimeResponse>(text)
                        pending.remove(response.id)?.complete(response)
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(stateLock) {
                if (generation != currentGeneration) return
                mutableSession.value = null
                mutableConnected.value = false
                opened.completeExceptionally(t)
                failPending(t)
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(stateLock) {
                if (generation != currentGeneration) return
                mutableSession.value = null
                mutableConnected.value = false
                failPending(IllegalStateException("连接已断开"))
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })
        }
        try { withTimeout(10_000) { opened.await() } }
        catch (e: Exception) { synchronized(stateLock) { if(generation==currentGeneration)disconnect() };throw e }
        return status
    }

    internal fun transferTarget(): RuntimeTransferTarget = synchronized(stateLock) {
        check(connected.value) { "请先连接设备" }
        val session=checkNotNull(mutableSession.value) { "请先连接设备" }
        RuntimeTransferTarget(session.host,session.port,session.generation)
    }
    internal fun checkTransferTarget(target: RuntimeTransferTarget) = synchronized(stateLock) {
        val session=mutableSession.value
        check(currentGeneration == target.generation && connected.value && session?.host==target.host && session.port==target.port) { "目标连接已改变" }
    }

    suspend fun downloadFile(path: String, root: String = "downloads"): Pair<String, ByteArray> {
        val target = transferTarget()
        val artifact = json.decodeFromString<FileArtifact>(requestAt(target, "files.download", kotlinx.serialization.json.buildJsonObject { put("root", kotlinx.serialization.json.JsonPrimitive(root));put("path", kotlinx.serialization.json.JsonPrimitive(path)) }))
        checkTransferTarget(target)
        val bytes = artifacts.downloadFile(target.host, target.port, artifact)
        checkTransferTarget(target)
        return artifact.name to bytes
    }

    internal suspend fun appIcon(target: RuntimeTransferTarget, packageName: String): ByteArray {
        currentCoroutineContext().ensureActive()
        // A canceled row cannot retract a sent WebSocket request. Keep its icon permit
        // until the reply, disconnect or the request's existing timeout; only this
        // descriptor wait is protected, so a subsequent HTTP download stays cancellable.
        val output = withContext(NonCancellable) {
            requestAt(target, "apps.icon", kotlinx.serialization.json.buildJsonObject { put("package", kotlinx.serialization.json.JsonPrimitive(packageName)) })
        }
        currentCoroutineContext().ensureActive()
        checkTransferTarget(target)
        val fields = json.parseToJsonElement(output).jsonObject
        check(fields["package"]?.jsonPrimitive?.content == packageName) { "应用图标对象不匹配" }
        val artifact = json.decodeFromString<BinaryArtifact>(output)
        check(artifact.width == 96 && artifact.height == 96 && artifact.sizeBytes in 1..128*1024) { "应用图标尺寸无效" }
        checkTransferTarget(target)
        val bytes = artifacts.download(target.host, target.port, artifact)
        checkTransferTarget(target)
        return bytes
    }

    suspend fun screenshot(): ByteArray {
        val target = transferTarget()
        val artifact = json.decodeFromString<BinaryArtifact>(requestAt(target, "device.screenshot", JsonObject(emptyMap())))
        checkTransferTarget(target)
        val bytes = artifacts.download(target.host, target.port, artifact)
        checkTransferTarget(target)
        return bytes
    }

    /** Bind queued tool actions to the connection present when the user invokes them. */
    fun toolWriter(): suspend (String, JsonObject) -> String {
        val target = transferTarget()
        return { tool, arguments ->
            val result = requestAt(target, tool, arguments)
            checkTransferTarget(target)
            result
        }
    }

    fun textWriter(): suspend (String, ((RuntimeProgress) -> Unit)?) -> String {
        val target = transferTarget()
        val destination = checkNotNull(socket) { "请先连接设备" }
        checkTransferTarget(target)
        return { text, onProgress ->
            checkTransferTarget(target)
            val guarded = onProgress?.let { handler -> { event: RuntimeProgress ->
                if (runCatching { checkTransferTarget(target) }.isSuccess) handler(event)
            } }
            val result = requestOnSocket(destination, "text", null, JsonObject(emptyMap()), text, guarded)
            checkTransferTarget(target)
            result
        }
    }

    /** Capture the socket before checking generation: never resolve a new socket after the guard. */
    fun configurationWriter(): suspend (ModelProfile) -> Unit {
        val target = transferTarget()
        val destination = checkNotNull(socket) { "请先连接设备" }
        checkTransferTarget(target)
        return { profile ->
            checkTransferTarget(target)
            requestOnSocket(destination, "configure", null, kotlinx.serialization.json.buildJsonObject {
                put("baseUrl", kotlinx.serialization.json.JsonPrimitive(profile.baseUrl))
                put("apiKey", kotlinx.serialization.json.JsonPrimitive(profile.apiKey))
                put("model", kotlinx.serialization.json.JsonPrimitive(profile.model))
            }, null, null)
            checkTransferTarget(target)
        }
    }

    internal suspend fun requestAt(target: RuntimeTransferTarget, tool: String, arguments: JsonObject): String {
        val destination = checkNotNull(socket) { "请先连接设备" }
        checkTransferTarget(target)
        return requestOnSocket(destination, "tool", tool, arguments, null, null)
    }

    internal suspend fun approveFileAt(target: RuntimeTransferTarget, arguments: JsonObject): String {
        val destination = checkNotNull(socket) { "请先连接设备" }
        checkTransferTarget(target)
        return requestOnSocket(destination, "approve", null, arguments, null, null)
    }

    internal suspend fun approveAppAt(target: RuntimeTransferTarget, arguments: JsonObject): String {
        val destination = checkNotNull(socket) { "请先连接设备" }
        checkTransferTarget(target)
        return requestOnSocket(destination, "approve_app", null, arguments, null, null)
    }

    internal suspend fun approveInstallAt(target: RuntimeTransferTarget, arguments: JsonObject): String {
        val destination = checkNotNull(socket) { "请先连接设备" }
        checkTransferTarget(target)
        return requestOnSocket(destination, "approve_install", null, arguments, null, null)
    }

    internal suspend fun approveCacheAt(target: RuntimeTransferTarget, arguments: JsonObject): String {
        val destination = checkNotNull(socket) { "请先连接设备" }
        checkTransferTarget(target)
        return requestOnSocket(destination, "approve_cache", null, arguments, null, null)
    }

    internal suspend fun approveSystemAt(target: RuntimeTransferTarget, arguments: JsonObject): String {
        val destination = checkNotNull(socket) { "请先连接设备" }
        checkTransferTarget(target)
        return requestOnSocket(destination, "approve_system", null, arguments, null, null)
    }

    suspend fun request(type: String, tool: String? = null, arguments: JsonObject = JsonObject(emptyMap()), text: String? = null, onProgress: ((RuntimeProgress) -> Unit)? = null): String {
        check(connected.value) { "请先连接设备" }
        return requestOnSocket(checkNotNull(socket), type, tool, arguments, text, onProgress)
    }

    private suspend fun requestOnSocket(destination: WebSocket, type: String, tool: String?, arguments: JsonObject,
        text: String?, onProgress: ((RuntimeProgress) -> Unit)?): String {
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<RuntimeResponse>()
        pending[id] = result
        if (onProgress != null) progressHandlers[id] = onProgress
        try {
            check(destination.send(json.encodeToString(RuntimeRequest(id, type, tool = tool, arguments = arguments, text = text, progress = onProgress != null))) == true) { "发送失败" }
            val response = withTimeout(if (type == "text") 180_000L else 45_000L) { result.await() }
            check(response.protocol == PROTOCOL_VERSION) { "不兼容的响应协议" }
            check(response.ok) { response.error ?: "执行失败" }
            return response.result.orEmpty()
        } finally { pending.remove(id); progressHandlers.remove(id) }
    }

    private fun failPending(error: Throwable) { pending.values.forEach { it.completeExceptionally(error) }; pending.clear(); progressHandlers.clear() }
    fun disconnect() = synchronized(stateLock) {
        currentGeneration++
        endpoint = null
        mutableSession.value = null
        socket?.cancel(); socket = null; mutableConnected.value = false
        failPending(IllegalStateException("连接已关闭"))
    }
    override fun close() {
        disconnect(); artifacts.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
}
