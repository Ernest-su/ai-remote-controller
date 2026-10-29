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
        val reconnect = AutomaticConnection(this, maxAttempts = 5) { times += currentTime; times.size == 5 }
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
    @Test fun unreachableTargetStopsAfterThreeAttempts() = runTest {
        val times = mutableListOf<Long>()
        var exhausted = false
        val reconnect = AutomaticConnection(this, onExhausted = { exhausted = true }) {
            times += currentTime; false
        }
        reconnect.start(device)
        advanceUntilIdle()
        assertEquals(listOf(0L, 3000L, 9000L), times)
        assertTrue(exhausted)
        advanceTimeBy(120_000)
        assertEquals(3, times.size)
    }

    @Test fun backgroundRecoveryWaitsBetweenBurstsAndCanBeStopped() = runTest {
        val times = mutableListOf<Long>()
        val reconnect = AutomaticConnection(backgroundScope, cooldownMillis = 30_000) { times += currentTime; false }
        reconnect.start(device)
        advanceTimeBy(9_001); runCurrent()
        assertEquals(listOf(0L, 3000L, 9000L), times)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(39_000L, times[3])
        reconnect.stop(); advanceTimeBy(120_000); runCurrent()
        assertEquals(4, times.size)
    }
    @Test fun networkWakeupSkipsCooldownWithoutReplacingTheTarget() = runTest {
        var attempts = 0
        val reconnect = AutomaticConnection(backgroundScope, maxAttempts = 1, cooldownMillis = 30_000) { attempts++; attempts == 2 }
        reconnect.start(device); runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(1000); reconnect.wake(); runCurrent()
        assertEquals(2, attempts)
        assertFalse(reconnect.isRunning)
    }

}
