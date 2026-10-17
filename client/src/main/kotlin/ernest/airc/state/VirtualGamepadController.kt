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
    private var typeRevision = 0L
    private var generation = 0L
    private var job: Job? = null
    private val json = Json { encodeDefaults = true }

    fun change(type: GamepadType, transform: (VirtualGamepadInput) -> VirtualGamepadInput): Boolean = synchronized(lock) {
        if (type != state.value.type) false else change(transform)
    }

    fun replace(input: VirtualGamepadInput): Boolean = change { input }
    fun change(transform: (VirtualGamepadInput) -> VirtualGamepadInput): Boolean = synchronized(lock) {
        if (!state.value.running || state.value.starting || state.value.reconnecting || state.value.stopping || state.value.switchingType) return false
        val input = transform(state.value.input)
        if (!input.valid()) return false
        mutable.update { it.copy(input = input.copy(buttons = input.buttons.toList())) }
        true
    }

    fun selectType(type: GamepadType) = synchronized(lock) {
        if (type != state.value.type) {
            typeRevision++
            mutable.update { it.copy(type = type, input = VirtualGamepadInput(), switchingType = it.running) }
        }
    }

    private fun finishTypeSwitch(revision: Long) = synchronized(lock) {
        if (revision == typeRevision) mutable.update { it.copy(switchingType = false) }
    }

    fun start(available: Boolean, allowSynthetic: Boolean = false): Boolean = synchronized(lock) {
        if (!available || job != null) return false
        val token = ++generation
        mutable.value = VirtualGamepadState(type = state.value.type, running = true, starting = true)
        job = scope.launch(start = CoroutineStart.LAZY) { run(token, allowSynthetic) }
        job!!.start()
        true
    }

    private class RequestFailure(cause: Exception) : Exception(cause)

    private suspend fun run(token: Long, allowSynthetic: Boolean) {
        try {
            // All retries belong to the original connection, never a replacement target.
            val target = runtime?.transferTarget()
            val guard = guardFactory()
            suspend fun send(tool: String, args: JsonObject): String = try {
                if (runtime != null) runtime.requestAt(checkNotNull(target), tool, args) else request(tool, args)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { throw RequestFailure(error) }
            var failures = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                guard()
                try {
                    runSession(token, allowSynthetic, guard, ::send) { failures = 0 }
                    return
                } catch (error: RequestFailure) {
                    currentCoroutineContext().ensureActive()
                    guard()
                    if (++failures > 3) throw IllegalStateException("手柄自动重连失败，请重新开始：${error.cause?.message ?: "请求超时"}", error)
                    delay(150L shl (failures - 1))
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            synchronized(lock) { if (token == generation) mutable.update { it.copy(error = error.message ?: "虚拟手柄失败") } }
        } finally {
            synchronized(lock) {
                job = null
                mutable.update { it.copy(running = false, starting = false, reconnecting = false, stopping = false, switchingType = false, input = VirtualGamepadInput()) }
            }
        }
    }

    private suspend fun runSession(token: Long, allowSynthetic: Boolean, guard: () -> Unit,
        send: suspend (String, JsonObject) -> String, onStable: () -> Unit) {
        var synthetic = false
        var id: String? = null
        var opened: JsonObject? = null
        var recovering = false
        var healthySince: Long? = null
        fun healthy() {
            val now = System.nanoTime()
            val since = healthySince ?: now.also { healthySince = it }
            if (now - since >= 5_000_000_000L) onStable()
        }
        fun markRecovering() {
            recovering = true
            healthySince = null
            synchronized(lock) { if (token == generation) mutable.update {
                it.copy(reconnecting = true, starting = false, input = VirtualGamepadInput(), error = null)
            } }
        }
        try {
            guard()
            val capability = Json.parseToJsonElement(withTimeout(1500) {
                send("gamepad.virtual_capabilities", buildJsonObject {})
            }).jsonObject
            guard()
            if (allowSynthetic && capability["supported"]?.jsonPrimitive?.boolean == false &&
                capability["runtimeUid"]?.jsonPrimitive?.int != null &&
                capability["mode"]?.jsonPrimitive?.content == "uinput") {
                synthetic = true
                synchronized(lock) {
                    if (token == generation) mutable.update { it.copy(starting = false, reconnecting = false, mode = "synthetic", sequence = 0) }
                }
                var sequence = 0L
                var sent = VirtualGamepadInput()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    guard()
                    val (snapshot, revision) = synchronized(lock) { state.value to typeRevision }
                    val input = snapshot.input
                    val pulse = input.toPulse()
                    val tool = if (pulse.valid()) "gamepad.pulse" else "gamepad.reset"
                    if (pulse.valid() || input != sent || snapshot.switchingType) {
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
                        finishTypeSwitch(revision)
                        healthy()
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
                val session = Json.parseToJsonElement(withTimeout(1500) {
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
                    it.copy(starting = false, reconnecting = false, sequence = 0, mode = "uinput", deviceName = session["deviceName"]?.jsonPrimitive?.content)
                }
            }
            var sent: VirtualGamepadInput? = null
            var sentAt = 0L
            var sequence = 0L
            var missedReplies = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                guard()
                val (snapshot, revision) = synchronized(lock) { state.value to typeRevision }
                val input = snapshot.input
                val now = System.nanoTime()
                if (input != sent || snapshot.switchingType || now - sentAt >= 250_000_000L) {
                    sequence++
                    val reply = try {
                        Json.parseToJsonElement(withTimeout(if (missedReplies == 0) 1000 else 500) {
                            send("gamepad.virtual_update", buildJsonObject {
                                put("id", id); put("sequence", sequence)
                                put("state", json.encodeToJsonElement(VirtualGamepadInput.serializer(), input))
                            })
                        }).jsonObject
                    } catch (error: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        guard()
                        markRecovering()
                        sent = null
                        // A single lost acknowledgement is not proof of a lost lease.
                        // Probe it immediately with a newer, neutral snapshot, before
                        // the two-second deadman. Never repeat the held input.
                        if (++missedReplies >= 2) throw error
                        continue
                    }
                    guard()
                    check(reply["id"]?.jsonPrimitive?.content == id &&
                        reply["sequence"]?.jsonPrimitive?.long == sequence &&
                        reply["submitted"]?.jsonPrimitive?.boolean == true &&
                        reply["gameVerified"]?.jsonPrimitive?.boolean == false) { "虚拟手柄更新响应不匹配" }
                    finishTypeSwitch(revision)
                    missedReplies = 0
                    recovering = false
                    healthy()
                    sent = input; sentAt = System.nanoTime()
                    synchronized(lock) { if (token == generation) mutable.update { it.copy(sequence = sequence, reconnecting = false) } }
                }
                delay(33)
            }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            markRecovering()
            throw RequestFailure(IllegalStateException("手柄请求超时", error))
        } catch (error: RequestFailure) {
            markRecovering()
            throw error
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            synchronized(lock) { if (token == generation) mutable.update {
                it.copy(running = false, starting = false, stopping = true, input = VirtualGamepadInput(),
                    error = error.message ?: "虚拟手柄失败")
            } }
            throw error
        } finally {
            if (synthetic) withContext(NonCancellable) {
                withTimeoutOrNull(if (recovering) 500 else 1500) {
                    runCatching { guard(); send("gamepad.reset", buildJsonObject {}) }
                }
            }
            if (id != null) withContext(NonCancellable) {
                withTimeoutOrNull(if (recovering) 500 else 1500) {
                    runCatching {
                        guard()
                        send("gamepad.virtual_close", buildJsonObject { put("id", id) })
                    }
                }
            }
        }
    }

    fun stop() = synchronized(lock) {
        generation++
        mutable.update { it.copy(running = false, starting = false, reconnecting = false, stopping = job != null, switchingType = false, input = VirtualGamepadInput()) }
        job?.cancel()
    }
    fun clear() = synchronized(lock) {
        stop()
        mutable.value = VirtualGamepadState(type = state.value.type, stopping = job != null)
    }
}
