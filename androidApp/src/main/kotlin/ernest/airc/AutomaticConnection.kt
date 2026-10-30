package ernest.airc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Only one retry loop owns the target; explicit user actions cancel it. */
internal class AutomaticConnection(
    private val scope: CoroutineScope,
    private val maxAttempts: Int = 3,
    private val onExhausted: () -> Unit = {},
    private val cooldownMillis: Long? = null,
    private val onRetry: (Long) -> Unit = {},
    private val attempt: suspend (RememberedDevice) -> Boolean,
) {
    private var job: Job? = null
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    val isRunning: Boolean get() = job?.isActive == true
    fun wake() { wakeups.trySend(Unit) }
    private suspend fun waitForRetry(millis: Long) { withTimeoutOrNull(millis) { wakeups.receive() } }
    fun start(device: RememberedDevice, initialDelayMillis: Long = 0) {
        stop()
        job = scope.launch {
            delay(initialDelayMillis)
            while (true) {
                var retryDelay = 3_000L
                repeat(maxAttempts) { index ->
                    if (attempt(device)) return@launch
                    if (index + 1 < maxAttempts) {
                        onRetry(retryDelay)
                        waitForRetry(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                    }
                }
                onExhausted()
                val cooldown = cooldownMillis ?: return@launch
                onRetry(cooldown)
                waitForRetry(cooldown)
            }
        }
    }
    fun stop() { job?.cancel(); job = null; while (wakeups.tryReceive().isSuccess) {} }
}
