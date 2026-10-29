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

/** Prepared route -> explicit start -> active receiver -> credit-controlled PCM -> drain acknowledgment. */
class RuntimeVoiceStream(private val session: RuntimeSession, private val current: () -> RuntimeSession?) : AutoCloseable {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val ready = CompletableDeferred<Unit>()
    private val stopped = CompletableDeferred<Unit>()
    @Volatile private var opened = false
    @Volatile private var cancelling = false
    @Volatile private var initialized = false
    @Volatile private var failure: Throwable? = null
    @Volatile private var consumed = 0L
    @Volatile private var sent = 0L
    @Volatile private var window = 0L
    private var socket: WebSocket? = null
    private fun checkCurrent() { check(current() == session) { "目标连接已改变" }; failure?.let { throw it } }
    suspend fun open(settings: VoiceSettings) {
        val key = settings.validate()
        checkCurrent()
        socket = client.newWebSocket(Request.Builder().url(RuntimeConnection.url(session.host, session.port, "/voice")).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (current() != session || cancelling) { webSocket.cancel(); ready.completeExceptionally(IllegalStateException("语音准备已取消")); return }
                opened = true
                webSocket.send(buildJsonObject { put("packageName", settings.packageName); put("keyCode", key) }.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val reply = Json.parseToJsonElement(text).jsonObject
                    reply["error"]?.jsonPrimitive?.content?.let { error(it) }
                    when {
                        reply["prepared"]?.jsonPrimitive?.booleanOrNull == true -> {
                            check(window == 0L) { "重复的语音准备响应" }
                            window = reply.getValue("windowBytes").jsonPrimitive.long.also { check(it in 3200..128000) }
                            if (!cancelling) webSocket.send("start")
                        }
                        reply["ready"]?.jsonPrimitive?.booleanOrNull == true -> {
                            check(window > 0 && reply["sampleRate"]?.jsonPrimitive?.int == 16000 && reply["channels"]?.jsonPrimitive?.int == 1)
                            ready.complete(Unit)
                        }
                        reply["consumedBytes"] != null -> {
                            val next = reply.getValue("consumedBytes").jsonPrimitive.long
                            check(next >= consumed && next <= sent) { "语音消费计数不匹配" }
                            consumed = next
                        }
                        reply["stopped"]?.jsonPrimitive?.booleanOrNull == true -> {
                            if (!cancelling) {
                                check(reply["cancelled"]?.jsonPrimitive?.booleanOrNull == false) { "目标取消了语音发送" }
                                check(reply["receivedBytes"]?.jsonPrimitive?.long == sent && reply["injectedBytes"]?.jsonPrimitive?.long == sent) { "语音未完整送达目标" }
                            }
                            stopped.complete(Unit)
                        }
                        else -> error("无效的语音服务响应")
                    }
                } catch (error: Exception) { fail(error); webSocket.close(1000, "voice failed") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail(t) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped.isCompleted) fail(IllegalStateException("语音连接已关闭"))
                // An empty peer Close is reported as 1005, which cannot be sent on the wire.
                webSocket.close(if (code == 1005) 1000 else code, reason)
            }
        })
        withTimeout(15_000) { ready.await() }
        checkCurrent(); initialized = true
    }
    private fun fail(error: Throwable) { failure = error; ready.completeExceptionally(error); stopped.completeExceptionally(error) }
    suspend fun send(pcm: ByteArray, count: Int) {
        check(initialized && !stopped.isCompleted && !cancelling) { "语音服务尚未准备或已停止" }
        require(count in 2..3200 && count % 2 == 0 && count <= pcm.size) { "无效的语音数据" }
        val destination = checkNotNull(socket)
        kotlinx.coroutines.withTimeout(6000) {
            while (true) {
                checkCurrent()
                if (sent + count - consumed <= window && destination.queueSize() + count <= window) break
                kotlinx.coroutines.delay(5)
            }
        }
        sent += count // Publish before send; the peer can acknowledge on another thread immediately.
        check(destination.send(pcm.toByteString(0, count))) { "语音发送失败" }
    }
    suspend fun finish() {
        checkCurrent()
        check(initialized && !cancelling) { "语音接收端尚未就绪" }
        check(checkNotNull(socket).send("end")) { "语音结束发送失败" }
        withTimeout(8000) { stopped.await() }
        checkCurrent()
    }
    /** Cancellation discards queued audio; a normal finger release must call finish instead. */
    suspend fun stop() {
        cancelling = true
        val destination = socket ?: return
        if (failure == null && opened && !stopped.isCompleted) {
            destination.send("cancel")
            withTimeout(4000) { stopped.await() }
        }
    }
    override fun close() {
        cancelling = true; socket?.cancel(); socket = null
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
}
