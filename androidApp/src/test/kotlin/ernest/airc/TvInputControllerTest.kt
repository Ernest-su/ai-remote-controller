package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TvInputControllerTest {
    private val ready = TvImeStatus(true, true, true, true, "fixture.ime/.Keyboard")
    private class Backend : TvInputBackend {
        var info = TvImeStatus(true, true, true, true, "fixture.ime/.Keyboard")
        var commits = 0; var pastes = 0; var closed = 0; var enables = 0
        var failure = false
        var pending: CompletableDeferred<Unit>? = null
        var started = CompletableDeferred<Unit>()
        var ignoreCancellation = false
        override suspend fun status() = info
        override suspend fun enable(): TvImeStatus { enables++; return info }
        override suspend fun restore() = info.copy(selected = false)
        override suspend fun commit(text: String): TvImeStatus {
            commits++; started.complete(Unit)
            if (ignoreCancellation) withContext(NonCancellable) { pending?.await() } else pending?.await()
            check(!failure) { "未确认提交" }; return info
        }
        override suspend fun paste(text: String) { pastes++; check(!failure) { "未确认粘贴" } }
        override fun close() { closed++ }
    }
    private suspend fun TvInputController.idle() { withTimeout(5000) { state.first { !it.busy } } }

    @Test fun modesArePerDeviceAndNeverInstallOrSubmitImplicitly() = runBlocking {
        var device = "living-room"
        val saved = mutableMapOf<String, TvInputMode>()
        val backend = Backend()
        fun controller() = TvInputController(this, { "session" }, { device },
            { saved[it] ?: TvInputMode.PASTE }, { id, mode -> saved[id] = mode }, { backend })
        val input = controller()
        input.selectTarget(device)
        input.edit("中文草稿")
        input.mode(TvInputMode.IME)
        device = "bedroom"; input.selectTarget(device)
        assertEquals(TvInputMode.PASTE, input.state.value.mode)
        assertEquals("中文草稿", input.state.value.draft)
        device = "living-room"; input.selectTarget(device)
        assertEquals(TvInputMode.IME, input.state.value.mode)
        val restarted = controller(); restarted.selectTarget(device)
        assertEquals(TvInputMode.IME, restarted.state.value.mode)
        assertEquals("", restarted.state.value.draft)
        assertEquals(0, backend.enables + backend.commits + backend.pastes)
    }
    @Test fun onlyAcknowledgedSubmissionClearsDraftAndNeverFallsBackOrReplays() = runBlocking {
        val backend = Backend()
        val input = TvInputController(this, { "session" }, { "tv" }, { TvInputMode.PASTE }, { _, _ -> }, { backend })
        input.edit("中文 🎬")
        input.send(false); assertEquals(0, backend.pastes)
        input.mode(TvInputMode.IME)
        input.send(true); assertEquals(0, backend.commits) // Unchecked IME is not submitted.
        input.refresh(true); input.idle()
        backend.failure = true
        input.send(true); input.send(true); input.idle()
        assertEquals(1, backend.commits); assertEquals(0, backend.pastes)
        assertEquals("中文 🎬", input.state.value.draft)
        assertNotNull(input.state.value.error)
        backend.failure = false
        input.refresh(true); input.idle()
        input.send(true); input.idle()
        assertEquals("", input.state.value.draft)
        assertEquals(2, backend.commits)
        assertEquals(ready, input.state.value.ime)
        input.restore(true); input.idle()
        assertEquals(TvInputMode.PASTE, input.state.value.mode)
        assertFalse(input.state.value.ime!!.selected)
    }
    @Test fun cancelAndChangingTargetKeepDraftAndRejectLateCompletion() = runBlocking {
        val backend = Backend().apply { pending = CompletableDeferred() }
        var device = "old"
        val input = TvInputController(this, { "session" }, { device }, { TvInputMode.IME }, { _, _ -> }, { backend })
        input.refresh(true); input.idle(); input.edit("不重复输入")
        input.send(true); backend.started.await()
        input.cancel(); input.idle()
        assertEquals("不重复输入", input.state.value.draft)
        assertEquals(1, backend.commits); assertEquals(0, backend.pastes)
        backend.pending = null
        input.refresh(true); input.idle()
        backend.pending = CompletableDeferred()
        backend.started = CompletableDeferred()
        backend.ignoreCancellation = true
        input.send(true)
        backend.started.await()
        device = "new"; input.selectTarget(device)
        input.edit("新设备草稿")
        backend.pending!!.complete(Unit)
        delay(100)
        assertEquals("新设备草稿", input.state.value.draft)
        assertNull(input.state.value.ime)
        assertNull(input.state.value.message)
        assertTrue(backend.closed >= 3)
    }
    @Test fun commandEncodesUnicodeAndShellCharactersAndRejectsOversizeOrBlank() {
        val text = "中文 🎬 ; \$(reboot) ' \n下一行"
        val command = imeCommitCommand(10, text)
        assertFalse(command.contains("reboot"))
        assertEquals(text, String(Base64.getDecoder().decode(command.substringAfter("--extra text:s:")), Charsets.UTF_8))
        assertTrue(command.contains("--user 10"))
        for (invalid in listOf(" ", "中".repeat(21846))) {
            assertThrows(IllegalArgumentException::class.java) { imeCommitCommand(0, invalid) }
        }
        assertEquals("committed", imeReplyStatus("Result: Bundle[{status=committed}]"))
        assertThrows(IllegalStateException::class.java) { imeReplyStatus("Error: no provider") }
    }
}
