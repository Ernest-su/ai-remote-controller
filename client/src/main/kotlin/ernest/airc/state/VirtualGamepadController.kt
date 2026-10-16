package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Keeps one latest snapshot, one in-flight update and bounded heartbeat cadence. */
class VirtualGamepadController(
    private val scope: CoroutineScope,
    private val request: suspend (String, JsonObject) -> String,
    private val guardFactory: () -> (() -> Unit) = { {} },
    private val runtime: RuntimeConnection? = null,
) {
    constructor(scope: CoroutineScope, runtime: RuntimeConnection) : this(scope,
        { tool, args -> runtime.request("tool", tool, args) },
        { val target = runtime.transferTarget(); { runtime.checkTransferTarget(target) } }, runtime)

    private val mutable = MutableStateFlow(VirtualGamepadState())
    val state = mutable.asStateFlow()
    private val lock = Any()
    private var generation = 0L
    private var job: Job? = null
    private val json = Json { encodeDefaults = true }

    fun replace(input: VirtualGamepadInput): Boolean = change { input }
    fun change(transform: (VirtualGamepadInput) -> VirtualGamepadInput): Boolean = synchronized(lock) {
        if (!state.value.running || state.value.starting || state.value.stopping) return false
        val input = transform(state.value.input)
        if (!input.valid()) return false
        mutable.update { it.copy(input = input.copy(buttons = input.buttons.toList())) }
        true
    }

    fun start(available: Boolean, allowSynthetic: Boolean = false): Boolean = synchronized(lock) {
        if (!available || job != null) return false
        val token = ++generation
        mutable.value = VirtualGamepadState(running = true, starting = true)
        job = scope.launch(start = CoroutineStart.LAZY) { run(token, allowSynthetic) }
        job!!.start()
        true
    }

    private suspend fun run(token: Long, allowSynthetic: Boolean) {
        var synthetic = false
        var id: String? = null
        var guard: (() -> Unit)? = null
        var opened: JsonObject? = null
        var target: RuntimeTransferTarget? = null
        suspend fun send(tool: String, args: JsonObject): String =
            if (runtime != null) runtime.requestAt(checkNotNull(target), tool, args) else request(tool, args)
        try {
            target = runtime?.transferTarget()
            guard = guardFactory()
            guard()
            val capability = Json.parseToJsonElement(withTimeout(5000) {
                send("gamepad.virtual_capabilities", buildJsonObject {})
            }).jsonObject
            guard()
            if (allowSynthetic && capability["supported"]?.jsonPrimitive?.boolean == false &&
                capability["runtimeUid"]?.jsonPrimitive?.int != null &&
                capability["mode"]?.jsonPrimitive?.content == "uinput") {
                synthetic = true
                synchronized(lock) {
                    if (token == generation) mutable.update { it.copy(starting = false, mode = "synthetic") }
                }
                var sequence = 0L
                var sent = VirtualGamepadInput()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    guard()
                    val input = synchronized(lock) { state.value.input }
                    val pulse = GamepadPulse(input.buttons, input.leftX, input.leftY, 100, input.rightX, input.rightY)
                    val tool = if (pulse.valid()) "gamepad.pulse" else "gamepad.reset"
                    if (pulse.valid() || input != sent) {
                        val args = if (tool == "gamepad.pulse") json.encodeToJsonElement(GamepadPulse.serializer(), pulse).jsonObject
                            else buildJsonObject {}
                        // Finish each finite transaction before cleanup or another snapshot.
                        val reply = Json.parseToJsonElement(withContext(NonCancellable) {
                            withTimeout(5000) { send(tool, args) }
                        }).jsonObject
                        guard()
                        check(reply["submitted"]?.jsonPrimitive?.boolean == true &&
                            reply["released"]?.jsonPrimitive?.boolean == true &&
                            reply["sourceGamepad"]?.jsonPrimitive?.int == 1025 &&
                            reply["sourceJoystick"]?.jsonPrimitive?.int == 16777232 &&
                            reply["operation"]?.jsonPrimitive?.content == tool.substringAfter('.') &&
                            reply["gameVerified"]?.jsonPrimitive?.boolean == false) { "手柄输入响应不匹配或释放未确认" }
                        sent = input
                        sequence++
                        synchronized(lock) { if (token == generation) mutable.update { it.copy(sequence = sequence) } }
                    }
                    delay(33)
                }
            }
            check(capability["supported"]?.jsonPrimitive?.boolean == true &&
                capability["runtimeUid"]?.jsonPrimitive?.int == 0 &&
                capability["mode"]?.jsonPrimitive?.content == "uinput") {
                capability["reason"]?.jsonPrimitive?.content ?: "目标不支持 root 虚拟手柄"
            }
            currentCoroutineContext().ensureActive()
            // Open is a short transaction: finish its bounded reply even when the
            // page stops, so its returned lease can be retired rather than lost.
            withContext(NonCancellable) {
                val session = Json.parseToJsonElement(withTimeout(5000) {
                    send("gamepad.virtual_open", buildJsonObject {})
                }).jsonObject
                id = session["id"]?.jsonPrimitive?.content?.takeIf {
                    it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                }
                opened = session
            }
            val session = checkNotNull(opened)
            guard()
            check(id != null && session["mode"]?.jsonPrimitive?.content == "uinput" &&
                session["registered"]?.jsonPrimitive?.boolean == true &&
                session["gameVerified"]?.jsonPrimitive?.boolean == false &&
                session["deadmanMs"]?.jsonPrimitive?.int == 2000) { "虚拟手柄会话响应不匹配" }
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (token == generation) mutable.update {
                    it.copy(starting = false, mode = "uinput", deviceName = session["deviceName"]?.jsonPrimitive?.content)
                }
            }
            var sent: VirtualGamepadInput? = null
            var sentAt = 0L
            var sequence = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                guard()
                val input = synchronized(lock) { state.value.input }
                val now = System.nanoTime()
                if (input != sent || now - sentAt >= 250_000_000L) {
                    sequence++
                    val reply = Json.parseToJsonElement(withTimeout(1000) {
                        send("gamepad.virtual_update", buildJsonObject {
                            put("id", id); put("sequence", sequence)
                            put("state", json.encodeToJsonElement(VirtualGamepadInput.serializer(), input))
                        })
                    }).jsonObject
                    guard()
                    check(reply["id"]?.jsonPrimitive?.content == id &&
                        reply["sequence"]?.jsonPrimitive?.long == sequence &&
                        reply["submitted"]?.jsonPrimitive?.boolean == true &&
                        reply["gameVerified"]?.jsonPrimitive?.boolean == false) { "虚拟手柄更新响应不匹配" }
                    sent = input; sentAt = System.nanoTime()
                    synchronized(lock) { if (token == generation) mutable.update { it.copy(sequence = sequence) } }
                }
                delay(33)
            }
        } catch (error: TimeoutCancellationException) {
            synchronized(lock) { if (token == generation) mutable.update { it.copy(error = "虚拟手柄请求超时，已停止输入") } }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            synchronized(lock) { if (token == generation) mutable.update { it.copy(error = error.message ?: "虚拟手柄失败") } }
        } finally {
            if (synthetic && guard != null) withContext(NonCancellable) {
                withTimeoutOrNull(5000) {
                    runCatching { guard(); send("gamepad.reset", buildJsonObject {}) }
                }
            }
            if (id != null && guard != null) withContext(NonCancellable) {
                withTimeoutOrNull(5000) {
                    runCatching {
                        guard()
                        send("gamepad.virtual_close", buildJsonObject { put("id", id) })
                    }
                }
            }
            synchronized(lock) {
                job = null
                mutable.update { it.copy(running = false, starting = false, stopping = false, input = VirtualGamepadInput()) }
            }
        }
    }

    fun stop() = synchronized(lock) {
        generation++
        mutable.update { it.copy(running = false, starting = false, stopping = job != null, input = VirtualGamepadInput()) }
        job?.cancel()
    }
    fun clear() = synchronized(lock) {
        stop()
        mutable.value = VirtualGamepadState(stopping = job != null)
    }
}
