package ernest.airc

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomaticConnectionTest {
    private val device = RememberedDevice("fixture", "TV", ConnectionMethod.TCP, lastConnectedAt = 1)

    @Test fun retriesWithBackoffUntilConnected() = runTest {
        val times = mutableListOf<Long>()
        val reconnect = AutomaticConnection(this) { times += currentTime; times.size == 5 }
        reconnect.start(device)
        advanceUntilIdle()
        assertEquals(listOf(0L, 3000L, 9000L, 21000L, 45000L), times)
    }

    @Test fun explicitStopCancelsAttemptAndFutureRetries() = runTest {
        var attempts = 0
        var canceled = false
        val reconnect = AutomaticConnection(this) {
            attempts++
            try { awaitCancellation() } finally { canceled = true }
        }
        reconnect.start(device)
        runCurrent()
        reconnect.stop()
        advanceUntilIdle()
        assertTrue(canceled)
        assertEquals(1, attempts)
    }

    @Test fun changingTargetCancelsPreviousRetry() = runTest {
        val ids = mutableListOf<String>()
        val reconnect = AutomaticConnection(this) { ids += it.id; it.id == "new" }
        reconnect.start(device)
        runCurrent()
        reconnect.start(device.copy(id = "new"))
        advanceUntilIdle()
        assertEquals(listOf("fixture", "new"), ids)
    }
}
