package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** One bounded sequence pinned to its connection. Pausing never resubmits an action. */
class QuickRoutineController(private val scope: CoroutineScope, private val runtime: RuntimeConnection? = null,
    initialHistory: List<RoutineExecution> = emptyList(), private val onHistoryChanged: (List<RoutineExecution>) -> Unit = {},
    private val request: suspend (String, JsonObject) -> String) {
    constructor(scope: CoroutineScope, runtime: RuntimeConnection, initialHistory: List<RoutineExecution> = emptyList(),
        onHistoryChanged: (List<RoutineExecution>) -> Unit = {}) : this(scope, runtime, initialHistory, onHistoryChanged, { tool, args -> runtime.request("tool", tool, args) })
    private val mutable = MutableStateFlow(RoutineRunState())
    val state = mutable.asStateFlow()
    private val historyMutable = MutableStateFlow(initialHistory.take(100).map { it.afterRestart() })
    val history = historyMutable.asStateFlow()
    private var job: Job? = null
    private val historyLock = Any()
    private val discarded = mutableSetOf<Pair<String?, String>>()
    fun cancel() { job?.cancel() }
    fun pause() { mutable.update { if (it.busy) it.copy(pauseRequested = true) else it } }
    fun resume() { mutable.update { it.copy(pauseRequested = false, paused = false) } }
    fun forget(deviceId: String, routineId: String? = null) = synchronized(historyLock) {
        discarded += history.value.filter { it.deviceId == deviceId && (routineId == null || it.routine.id == routineId) }.map { it.deviceId to it.routine.id }
        historyMutable.update { list -> list.filterNot { it.deviceId == deviceId && (routineId == null || it.routine.id == routineId) } }
        onHistoryChanged(history.value)
    }
    fun start(routine: QuickRoutine, available: Boolean, deviceId: String? = null, targetName: String? = null): Boolean {
        if (!available || state.value.busy || job?.isActive == true || !routine.valid()) return false
        val connection = runtime
        val target = if (connection != null) runCatching { connection.transferTarget() }.getOrNull() ?: return false else null
        synchronized(historyLock) { discarded.remove(deviceId to routine.id) }
        var execution = RoutineExecution(routine, deviceId, targetName, System.currentTimeMillis())
        fun publish(change: (RoutineExecution) -> RoutineExecution) {
            execution = change(execution)
            synchronized(historyLock) {
                if ((deviceId to routine.id) in discarded) return
                historyMutable.update { list -> (listOf(execution) + list.filterNot { it.deviceId == deviceId && it.routine.id == routine.id }).take(100) }
                onHistoryChanged(history.value)
            }
        }
        fun finishStep(status: RoutineStepStatus, output: String? = null, error: String? = null) {
            publish { it.copy(steps = it.steps.mapIndexed { i, step -> if (i == state.value.index - 1 && step.status == RoutineStepStatus.RUNNING)
                step.copy(status = status, finishedAt = System.currentTimeMillis(), output = output?.take(8192) ?: step.output, error = error?.take(8192)) else step }) }
        }
        mutable.value = RoutineRunState(busy = true, total = routine.steps.size, routineId = routine.id)
        publish { it }
        job = scope.launch {
            suspend fun awaitRunning() {
                ensureActive()
                if (state.value.pauseRequested) {
                    mutable.update { it.copy(paused = true) }
                    publish { it.copy(status = RoutineStatus.PAUSED) }
                    state.first { !it.pauseRequested }
                    ensureActive()
                    mutable.update { it.copy(paused = false) }
                    publish { it.copy(status = RoutineStatus.RUNNING) }
                }
                if (runtime != null) runtime.checkTransferTarget(checkNotNull(target))
            }
            try {
                // Paused time does not consume the execution budget.
                withTimeout(120000 + 30 * 60 * 1000L) {
                    val started = System.nanoTime()
                    var pausedNanos = 0L
                    fun checkBudget() {
                        check((System.nanoTime() - started - pausedNanos) / 1_000_000 < 120000) { "执行超时，已停止后续步骤。" }
                    }
                    suspend fun checkpoint() {
                        val before = System.nanoTime()
                        awaitRunning()
                        pausedNanos += System.nanoTime() - before
                        checkBudget()
                    }
                    routine.steps.forEachIndexed { index, step ->
                        checkpoint()
                        mutable.update { it.copy(index = index + 1, message = step.description()) }
                        publish { it.copy(steps = it.steps.mapIndexed { i, value -> if (i == index) value.copy(status = RoutineStepStatus.RUNNING, startedAt = System.currentTimeMillis()) else value }) }
                        if (step.type == "wait") {
                            var activeWait = step.value.toLong()
                            while (activeWait > 0) {
                                checkpoint()
                                val before = System.nanoTime()
                                delay(minOf(activeWait, 50))
                                activeWait -= ((System.nanoTime() - before) / 1_000_000).coerceAtLeast(1)
                            }
                            finishStep(RoutineStepStatus.SUCCEEDED)
                        } else {
                            val tool = if (step.type == "app") "apps.open" else "remote.key"
                            val args = buildJsonObject { put(if (step.type == "app") "package" else "key", step.value) }
                            val output = withTimeout(15000) { if (runtime != null) runtime.requestAt(checkNotNull(target), tool, args) else request(tool, args) }
                            publish { it.copy(steps = it.steps.mapIndexed { i, result -> if (i == index) result.copy(output = output.take(8192)) else result }) }
                            if (step.type == "key") {
                                val reply = Json.parseToJsonElement(output).jsonObject
                                check(reply["operation"]?.jsonPrimitive?.content == "key" && reply["submitted"]?.jsonPrimitive?.booleanOrNull == true && reply["released"]?.jsonPrimitive?.booleanOrNull == true) { "按键提交或释放未确认；已停止后续步骤，请核对电视。" }
                            }
                            finishStep(RoutineStepStatus.SUCCEEDED, output)
                        }
                    }
                    ensureActive()
                    checkBudget()
                    if (runtime != null) runtime.checkTransferTarget(checkNotNull(target))
                }
                mutable.update { it.copy(message = "步骤已提交，请核对电视实际画面。") }
                publish { it.copy(status = RoutineStatus.SUCCEEDED, finishedAt = System.currentTimeMillis()) }
            } catch (e: TimeoutCancellationException) {
                val error = "执行超时，已停止后续步骤；已提交动作不会撤销。"
                finishStep(RoutineStepStatus.INTERRUPTED, error = error)
                mutable.update { it.copy(error = error) }
                publish { it.copy(status = RoutineStatus.FAILED, finishedAt = System.currentTimeMillis(), error = error) }
            } catch (e: CancellationException) {
                finishStep(RoutineStepStatus.INTERRUPTED)
                mutable.update { it.copy(message = "已停止后续步骤；已提交动作不会撤销。") }
                publish { it.copy(status = RoutineStatus.STOPPED, finishedAt = System.currentTimeMillis()) }
                throw e
            } catch (e: Exception) {
                val error = (e.message ?: "执行失败，已停止后续步骤。").take(8192)
                finishStep(RoutineStepStatus.FAILED, error = error)
                mutable.update { it.copy(error = error) }
                publish { it.copy(status = RoutineStatus.FAILED, finishedAt = System.currentTimeMillis(), error = error) }
            } finally { mutable.update { it.copy(busy = false, paused = false, pauseRequested = false) } }
        }
        return true
    }
}
