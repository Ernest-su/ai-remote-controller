package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import kotlinx.serialization.json.*

/** Session-bound last requested physical display mode; never infers Android sleep state. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenPowerController(scope: CoroutineScope, private val runtime: RuntimeConnection,
    private val onError: (String) -> Unit) {
    private val mutable = MutableStateFlow<Boolean?>(null)
    val state = mutable.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val foreground = MutableStateFlow(true)
    fun setForeground(active: Boolean) { foreground.value = active }
    private val commands = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    init {
        scope.launch {
            runtime.session.combine(foreground) { session, active -> session.takeIf { active } }.collectLatest { session ->
                mutable.value = null
                mutableBusy.value = false
                while (commands.tryReceive().isSuccess) { }
                if (session == null) return@collectLatest
                runtime.connected.first { it }
                val target = runtime.transferTarget()
                suspend fun request(on: Boolean? = null, reportError: Boolean = on != null): Boolean {
                    try {
                        val output = runtime.requestAt(target, "screen.power", buildJsonObject { if (on != null) put("on", on) })
                        runtime.checkTransferTarget(target)
                        mutable.value = Json.parseToJsonElement(output).jsonObject["on"]?.jsonPrimitive?.boolean
                            ?: error("目标未报告屏幕状态")
                        return true
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        if (reportError && runCatching { runtime.checkTransferTarget(target) }.isSuccess)
                            onError(error.message ?: "屏幕控制失败")
                        return false
                    }
                }
                request()
                while (currentCoroutineContext().isActive) {
                    val toggle = select<Boolean> { commands.onReceive { true }; onTimeout(3_000) { false } }
                    if (!toggle) request()
                    else {
                        try { if (request(reportError = true)) mutable.value?.let { request(!it) } }
                        finally { mutableBusy.value = false }
                    }
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
