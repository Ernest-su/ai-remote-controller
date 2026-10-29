package ernest.airc

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AdbAttemptTest {
    @Test fun successKeepsTheAuthorizedSessionOpen() = runBlocking {
        var closed = false
        assertEquals("ready", adbAttempt(1000, { closed = true }) { "ready" })
        assertFalse(closed)
    }
    @Test fun timeoutClosesCandidateAndUnblocksSocketRead() {
        val closed = CountDownLatch(1)
        assertThrows(TimeoutCancellationException::class.java) { runBlocking {
            adbAttempt(50, closed::countDown) {
                withContext(Dispatchers.IO) { assertTrue("Timed-out handshake must close its socket", closed.await(2, TimeUnit.SECONDS)) }
            }
        } }
        assertEquals(0, closed.count)
    }
    @Test fun explicitCancellationAlsoClosesTheUnpublishedCandidate() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        val job = launch {
            adbAttempt(5000, { closes.incrementAndGet(); closed.countDown() }) {
                withContext(Dispatchers.IO) { entered.complete(Unit); assertTrue(closed.await(2, TimeUnit.SECONDS)) }
            }
        }
        entered.await(); job.cancelAndJoin()
        assertEquals(1, closes.get())
    }
}
