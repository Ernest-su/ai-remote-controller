package ernest.airc

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Response

suspend fun <T> execute(call: Call, consume: (Response) -> T): T = coroutineScope {
    val monitor = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try { withContext(Dispatchers.IO) { call.execute().use(consume) } }
    catch (error: Exception) {
        // Socket closure may report IOException; preserve the caller's cancellation
        // instead of treating an intentional stop as a failed network operation.
        currentCoroutineContext().ensureActive()
        throw error
    }
    finally { monitor.cancel() }
}
