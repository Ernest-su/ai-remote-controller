package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TvInputControllerTest {
    private val ready = TvImeStatus(true, true, true, true, "fixture.ime/.Keyboard")
    private class Backend : TvInputBackend {
        @Volatile var info = TvImeStatus(true, true, true, true, "fixture.ime/.Keyboard")
        var commits = 0; var pastes = 0; var closed = 0; var enables = 0
        @Volatile var statusFailure = false
        var failure = false
        var pending: CompletableDeferred<Unit>? = null
        var started = CompletableDeferred<Unit>()
        var ignoreCancellation = false
        override suspend fun status(): TvImeStatus { check(!statusFailure); return info }
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
    @Test fun focusMonitorTracksRealReadinessWithoutInstallingOrSubmittingAndClosesOnBackground() = runBlocking {
        val backend = Backend().apply { info = ready.copy(ready = false) }
        val input = TvInputController(this, { "session" }, { "tv" }, { TvInputMode.PASTE }, { _, _ -> }, { backend })
        val monitor = launch { input.monitorFocus(10, 10) }
        delay(50)
        assertNull(input.focusedEditor.value)
        backend.info = ready
        withTimeout(2000) { input.focusedEditor.first { it != null } }
        backend.info = ready.copy(ready = false)
        withTimeout(2000) { input.focusedEditor.first { it == null } }
        backend.info = ready
        withTimeout(2000) { input.focusedEditor.first { it != null } }
        backend.statusFailure = true
        withTimeout(2000) { input.focusedEditor.first { it == null } }
        backend.statusFailure = false
        withTimeout(2000) { input.focusedEditor.first { it != null } }
        input.cancel() // Cancelling an input operation must not permanently disable monitoring.
        withTimeout(2000) { input.focusedEditor.first { it != null } }
        monitor.cancelAndJoin()
        assertNull(input.focusedEditor.value)
        assertTrue(backend.closed > 0)
        assertEquals(0, backend.enables + backend.commits + backend.pastes)
    }

    @Test fun focusMonitorRejectsLateStatusAfterSwitchingTelevision() = runBlocking {
        var device = "old"
        val waiting = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var closed = false
        val backend = object : TvInputBackend {
            override suspend fun status(): TvImeStatus {
                entered.complete(Unit)
                withContext(NonCancellable) { waiting.await() }
                return ready
            }
            override suspend fun enable(): TvImeStatus = error("No implicit activation")
            override suspend fun restore(): TvImeStatus = error("No implicit restore")
            override suspend fun commit(text: String): TvImeStatus = error("No implicit commit")
            override suspend fun paste(text: String) = error("No implicit paste")
            override fun close() { closed = true }
        }
        val input = TvInputController(this, { "session" }, { device }, { TvInputMode.PASTE }, { _, _ -> }, { backend })
        val monitor = launch { input.monitorFocus(10, 10) }
        entered.await()
        device = "new"; input.selectTarget(device)
        waiting.complete(Unit)
        monitor.join()
        assertNull(input.focusedEditor.value)
        assertTrue(closed)
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
