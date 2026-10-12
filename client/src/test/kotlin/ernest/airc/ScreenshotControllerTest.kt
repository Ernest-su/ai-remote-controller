package ernest.airc

import kotlinx.coroutines.*
import kotlin.test.*

class ScreenshotControllerTest {
    @Test fun cancelledCaptureAllowsTheNextRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var captures = 0
        val controller = ScreenshotController(scope) {
            captures++
            if (captures == 1) throw CancellationException("capture cancelled")
            byteArrayOf(3)
        }
        try {
            controller.refresh(true)
            assertFalse(controller.state.value.loading)
            assertNull(controller.state.value.error)
            controller.refresh(true)
            assertEquals(2, captures)
            assertContentEquals(byteArrayOf(3), controller.state.value.png)
            assertFalse(controller.state.value.loading)
        } finally { scope.cancel() }
    }

    @Test fun clearDiscardsOldCaptureAndExportKeepsExplicitlyChosenSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = CompletableDeferred<ByteArray>()
        val controller = ScreenshotController(scope) { withContext(NonCancellable) { pending.await() } }
        try {
            controller.refresh(false); assertFalse(controller.state.value.loading)
            controller.refresh(true); assertTrue(controller.state.value.loading)
            controller.clear(); pending.complete(byteArrayOf(1)); yield()
            assertNull(controller.state.value.png)
            val ready = ScreenshotController(scope) { byteArrayOf(2) }
            ready.refresh(true)
            assertContentEquals(byteArrayOf(2), ready.state.value.png)
            ready.prepareExport(); ready.clear()
            assertContentEquals(byteArrayOf(2), ready.takeExport())
            assertNull(ready.takeExport())
        } finally { scope.cancel() }
    }
}
