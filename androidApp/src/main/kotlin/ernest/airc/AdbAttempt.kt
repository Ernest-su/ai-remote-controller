package ernest.airc

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/** Closing the candidate socket interrupts a blocking handshake before it becomes a session. */
internal suspend fun <T> adbAttempt(timeoutMillis: Long, close: () -> Unit, action: suspend () -> T): T = withTimeout(timeoutMillis) {
    coroutineScope {
        val finished = AtomicBoolean(false)
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { if (!finished.get()) runCatching(close) }
        }
        try { action() }
        finally { finished.set(true); cancellation.cancel() }
    }
}
