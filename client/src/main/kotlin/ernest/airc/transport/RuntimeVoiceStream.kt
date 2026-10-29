package ernest.airc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

data class VoiceSettings(val packageName: String = "com.konka.bestlauncher", val keyCode: String = "1017") {
    fun validate(): Int {
        require(packageName.length <= 255 && packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "请输入接收语音的应用包名" }
        return if (keyCode.isBlank()) 0 else keyCode.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("附加键值须为 1–65535 的整数，留空则不发送按键")
    }
}

/** Independent short-lived stream, pinned to one runtime connection generation. */
class RuntimeVoiceStream(private val session: RuntimeSession, private val current: () -> RuntimeSession?) : AutoCloseable {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val ready = CompletableDeferred<Unit>()
    private val stopped = CompletableDeferred<Unit>()
    @Volatile private var initialized = false
    @Volatile private var failure: Throwable? = null
    private var socket: WebSocket? = null
    private fun checkCurrent() { check(current() == session) { "目标连接已改变" }; failure?.let { throw it } }
    suspend fun open(settings: VoiceSettings) {
        val key = settings.validate()
        checkCurrent()
        socket = client.newWebSocket(Request.Builder().url(RuntimeConnection.url(session.host, session.port, "/voice")).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (current() != session) { webSocket.cancel(); ready.completeExceptionally(IllegalStateException("目标连接已改变")); return }
                webSocket.send(buildJsonObject { put("packageName", settings.packageName); put("keyCode", key) }.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val reply = Json.parseToJsonElement(text).jsonObject
                    reply["error"]?.jsonPrimitive?.content?.let { error(it) }
                    if (reply["ready"]?.jsonPrimitive?.booleanOrNull == true) ready.complete(Unit)
                    else if (reply["stopped"]?.jsonPrimitive?.booleanOrNull == true) stopped.complete(Unit)
                    else error("无效的语音服务响应")
                } catch (error: Exception) { fail(error); webSocket.close(1000, "voice failed") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail(t) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped.isCompleted) fail(IllegalStateException("语音连接已关闭"))
                webSocket.close(code, reason)
            }
        })
        withTimeout(6000) { ready.await() }
        checkCurrent()
        initialized = true
    }
    private fun fail(error: Throwable) { failure = error; ready.completeExceptionally(error); stopped.completeExceptionally(error) }
    fun send(pcm: ByteArray, count: Int) {
        checkCurrent()
        check(initialized && !stopped.isCompleted) { "语音服务尚未准备或已停止" }
        require(count in 2..3200 && count % 2 == 0 && count <= pcm.size) { "无效的语音数据" }
        val destination = checkNotNull(socket)
        check(destination.queueSize() + count <= 6400) { "语音网络拥堵，请重试" }
        check(destination.send(pcm.toByteString(0, count))) { "语音发送失败" }
    }
    suspend fun stop() {
        val destination = socket ?: return
        if (failure == null && ready.isCompleted) {
            destination.send("stop")
            withTimeout(1500) { stopped.await() }
        }
    }
    override fun close() {
        socket?.cancel(); socket = null
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
}
