package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Session-bound last requested physical display mode; never infers Android sleep state. */
class ScreenPowerController(scope: CoroutineScope, private val runtime: RuntimeConnection,
    private val onError: (String) -> Unit) {
    private val mutable = MutableStateFlow<Boolean?>(null)
    val state = mutable.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val commands = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    init {
        scope.launch {
            runtime.session.collectLatest { session ->
                mutable.value = null
                mutableBusy.value = false
                while (commands.tryReceive().isSuccess) { }
                if (session == null) return@collectLatest
                runtime.connected.first { it }
                val target = runtime.transferTarget()
                suspend fun request(on: Boolean? = null) {
                    try {
                        val output = runtime.requestAt(target, "screen.power", buildJsonObject { if (on != null) put("on", on) })
                        runtime.checkTransferTarget(target)
                        mutable.value = Json.parseToJsonElement(output).jsonObject["on"]?.jsonPrimitive?.boolean
                            ?: error("目标未报告屏幕状态")
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        if (on != null && runCatching { runtime.checkTransferTarget(target) }.isSuccess)
                            onError(error.message ?: "屏幕控制失败")
                    }
                }
                request()
                for (command in commands) {
                    try { mutable.value?.let { request(!it) } }
                    finally { mutableBusy.value = false }
                }
            }
        }
    }
    fun toggle() {
        if (!runtime.connected.value || mutable.value == null || mutableBusy.value) return
        mutableBusy.value = true
        commands.trySend(Unit)
    }
}
