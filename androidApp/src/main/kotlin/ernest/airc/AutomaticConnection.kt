package ernest.airc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Only one retry loop owns the target; explicit user actions cancel it. */
internal class AutomaticConnection(
    private val scope: CoroutineScope,
    private val attempt: suspend (RememberedDevice) -> Boolean,
) {
    private var job: Job? = null
    fun start(device: RememberedDevice) {
        stop()
        job = scope.launch {
            var retryDelay = 3_000L
            while (!attempt(device)) {
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            }
        }
    }
    fun stop() { job?.cancel(); job = null }
}
