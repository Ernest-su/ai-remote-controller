package ernest.airc

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FileTaskNoticeIdentityTest {
    @Test fun repeatedIdenticalOperationsGetNewNoticeButCompletionKeepsIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var response = CompletableDeferred<String>()
        val controller = FileMediaController(scope) { _, _ -> response.await() }
        val result = """{"root":"downloads","path":"movie.mp4","mimeType":"video/mp4","transport":"content","packages":["fixture.player"]}"""
        try {
            controller.inspect("downloads", "movie.mp4", true)
            val first = controller.state.value.noticeId
            assertNotNull(first)
            response.complete(result)
            assertFalse(controller.state.value.busy)
            assertEquals(first, controller.state.value.noticeId)
            response = CompletableDeferred()
            controller.inspect("downloads", "movie.mp4", true)
            val second = controller.state.value.noticeId
            assertNotNull(second)
            assertNotEquals(first, second)
            response.complete(result)
            assertEquals(second, controller.state.value.noticeId)
            controller.clear()
            assertNull(controller.state.value.noticeId)
        } finally { scope.cancel() }
    }
}
