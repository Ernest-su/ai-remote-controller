package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

data class MouseState(val running: Boolean = false, val starting: Boolean = false, val error: String? = null)

/** One connection-bound writer, no action replay, bounded ordered button edges and coalesced motion. */
class MouseController(
    private val scope: CoroutineScope,
    private val writer: () -> (suspend (String, JsonObject) -> String),
) {
    constructor(scope: CoroutineScope, runtime: RuntimeConnection) : this(scope, runtime::toolWriter)
    private data class Frame(var dx: Int = 0, var dy: Int = 0, var wheel: Int = 0, val buttons: Int = 0)
    private val lock = Any()
    private val mutable = MutableStateFlow(MouseState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val pending = ArrayDeque<Frame>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var buttons = 0
    private var fractionX = 0f
    private var fractionY = 0f

    /** A new foreground visit waits for the previous connection-bound release before opening. */
    suspend fun activate(available: Boolean): Boolean {
        val previous = synchronized(lock) { stop(); job }
        previous?.join()
        currentCoroutineContext().ensureActive()
        return start(available)
    }

    fun start(available: Boolean): Boolean = synchronized(lock) {
        if (!available || job != null) return false
        val send = try { writer() } catch (error: Exception) {
            mutable.value = MouseState(error = error.message ?: "请先连接设备"); return false
        }
        pending.clear(); buttons = 0; fractionX = 0f; fractionY = 0f
        mutable.value = MouseState(starting = true)
        job = scope.launch(start = CoroutineStart.LAZY) {
            var id: String? = null
            try {
                val capabilities = Json.parseToJsonElement(withTimeout(1500) { send("mouse.capabilities", buildJsonObject {}) }).jsonObject
                check(capabilities["supported"]?.jsonPrimitive?.booleanOrNull == true) {
                    "目标无法创建鼠标，请在运行权限启用 root 后重试。${capabilities["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
                }
                val opened = Json.parseToJsonElement(withTimeout(1500) { send("mouse.open", buildJsonObject {}) }).jsonObject
                id = opened["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: error("鼠标启动响应缺少会话")
                // Let InputReader register the new device before the first edge.
                delay(150)
                synchronized(lock) { mutable.value = MouseState(running = true) }
                var sequence = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (synchronized(lock) { pending.isEmpty() }) withTimeoutOrNull(250) { wake.receive() }
                    val frame = synchronized(lock) { pending.removeFirstOrNull() ?: Frame(buttons = buttons) }
                    sequence++
                    val reply = Json.parseToJsonElement(withTimeout(1000) {
                        send("mouse.update", buildJsonObject {
                            put("id", id); put("sequence", sequence)
                            putJsonObject("frame") { put("dx", frame.dx); put("dy", frame.dy); put("wheel", frame.wheel); put("buttons", frame.buttons) }
                        })
                    }).jsonObject
                    check(reply["id"]?.jsonPrimitive?.content == id && reply["sequence"]?.jsonPrimitive?.longOrNull == sequence &&
                        reply["submitted"]?.jsonPrimitive?.booleanOrNull == true) { "鼠标输入响应不匹配" }
                    delay(32)
                }
            } catch (error: TimeoutCancellationException) {
                synchronized(lock) { mutable.value = MouseState(error = "鼠标请求超时，请退出鼠标页面后重进；未自动重发操作。") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                synchronized(lock) { mutable.value = MouseState(error = error.message ?: "鼠标输入失败") }
            } finally {
                id?.let { owned -> withContext(NonCancellable) {
                    try { withTimeout(1000) { send("mouse.close", buildJsonObject { put("id", owned) }) } }
                    catch (_: Exception) { /* Runtime deadman releases a lost connection within two seconds. */ }
                } }
                synchronized(lock) {
                    job = null; pending.clear(); buttons = 0; fractionX = 0f; fractionY = 0f
                    mutable.value = mutable.value.copy(running = false, starting = false)
                }
            }
        }
        job!!.start(); true
    }
    private fun enqueue(dx: Int = 0, dy: Int = 0, wheel: Int = 0, edge: Boolean = false) {
        val last = pending.lastOrNull()?.takeIf { !edge && it.buttons == buttons }
        if (last != null) {
            last.dx = (last.dx + dx).coerceIn(-2048,2048)
            last.dy = (last.dy + dy).coerceIn(-2048,2048)
            last.wheel = (last.wheel + wheel).coerceIn(-32,32)
        } else {
            if (pending.size >= 16) {
                stop(); mutable.value = MouseState(error = "鼠标操作过快，已停止并释放按键，请退出鼠标页面后重进。")
                return
            }
            pending.addLast(Frame(dx.coerceIn(-2048,2048),dy.coerceIn(-2048,2048),wheel.coerceIn(-32,32),buttons))
        }
        wake.trySend(Unit)
    }
    fun move(dx: Float, dy: Float) = synchronized(lock) {
        if (mutable.value.running && dx.isFinite() && dy.isFinite()) {
            fractionX += dx.coerceIn(-2048f,2048f); fractionY += dy.coerceIn(-2048f,2048f)
            val x = fractionX.roundToInt(); val y = fractionY.roundToInt()
            fractionX -= x; fractionY -= y
            if (x != 0 || y != 0) enqueue(x,y)
        }
    }
    fun scroll(steps: Int) = synchronized(lock) { if (mutable.value.running && steps != 0) enqueue(wheel = steps.coerceIn(-32,32)) }
    fun button(mask: Int, down: Boolean) = synchronized(lock) {
        if (mutable.value.running && mask in listOf(1,2,4)) {
            val next = if (down) buttons or mask else buttons and mask.inv()
            if (next != buttons) { buttons = next; enqueue(edge = true) }
        }
    }
    fun stop() = synchronized(lock) {
        mutable.value = mutable.value.copy(running = false, starting = false)
        pending.clear(); buttons = 0; job?.cancel()
    }
}
