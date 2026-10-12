package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ScreenshotState(val png: ByteArray? = null, val loading: Boolean = false, val error: String? = null)

class ScreenshotController(private val scope: CoroutineScope, private val capture: suspend () -> ByteArray) {
    private val mutable = MutableStateFlow(ScreenshotState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var pendingExport: ByteArray? = null
    fun prepareExport() { pendingExport = state.value.png }
    fun takeExport(): ByteArray? = pendingExport.also { pendingExport = null }
    fun refresh(available: Boolean) {
        if (!available || state.value.loading) return
        val token = generation
        mutable.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            try { val png = capture(); if (token == generation) mutable.value = ScreenshotState(png) }
            catch (e: CancellationException) {
                if (token == generation) mutable.update { it.copy(loading = false) }
                throw e
            }
            catch (e: Exception) { if (token == generation) mutable.update { it.copy(loading = false, error = e.message ?: "截图失败") } }
        }
    }
    fun clear() { generation++; job?.cancel(); job = null; mutable.value = ScreenshotState() }
}
