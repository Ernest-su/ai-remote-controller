package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** One bounded sequence, pinned to the initiating connection; never replays after interruption. */
class QuickRoutineController(private val scope: CoroutineScope, private val runtime: RuntimeConnection? = null,
    private val request: suspend (String, JsonObject) -> String) {
    constructor(scope: CoroutineScope, runtime: RuntimeConnection) : this(scope, runtime, { tool, args -> runtime.request("tool", tool, args) })
    private val mutable = MutableStateFlow(RoutineRunState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun cancel() { job?.cancel() }
    fun start(routine: QuickRoutine, available: Boolean): Boolean {
        if (!available || state.value.busy || !routine.valid()) return false
        val connection = runtime
        val target = if (connection != null) runCatching { connection.transferTarget() }.getOrNull() ?: return false else null
        mutable.value = RoutineRunState(busy = true, total = routine.steps.size)
        job = scope.launch {
            try {
                withTimeout(120000) {
                    routine.steps.forEachIndexed { index, step ->
                        ensureActive()
                        if (runtime != null) runtime.checkTransferTarget(checkNotNull(target))
                        mutable.update { it.copy(index = index + 1, message = step.description()) }
                        if (step.type == "wait") delay(step.value.toLong())
                        else {
                            val tool = if (step.type == "app") "apps.open" else "remote.key"
                            val args = buildJsonObject { put(if (step.type == "app") "package" else "key", step.value) }
                            if (runtime != null) runtime.requestAt(checkNotNull(target), tool, args) else request(tool, args)
                        }
                    }
                    if (runtime != null) runtime.checkTransferTarget(checkNotNull(target))
                }
                mutable.update { it.copy(message = "步骤已提交，请核对电视实际画面。") }
            } catch (e: TimeoutCancellationException) { mutable.update { it.copy(error = "执行超时，已停止后续步骤；已提交动作不会撤销。") } }
            catch (e: CancellationException) { mutable.update { it.copy(message = "已停止后续步骤；已提交动作不会撤销。") }; throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "执行失败，已停止后续步骤。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
        return true
    }
}
