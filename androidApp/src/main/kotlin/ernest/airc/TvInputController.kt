package ernest.airc

import android.content.Context
import ernest.ascrcpy.scrcpy.ScrcpyTextInput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TvInputState(val draft: String = "", val busy: Boolean = false, val message: String? = null, val error: String? = null)

/** Draft lives in ViewModel memory only; never saved as instance state or preferences. */
class TvInputController(private val scope: CoroutineScope, private val context: Context, private val runtime: RuntimeConnection,
    private val connect: suspend () -> AdbSession) {
    private val mutable = MutableStateFlow(TvInputState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun edit(value: String) { if (!state.value.busy) mutable.update { it.copy(draft = value, message = null, error = null) } }
    fun cancel() { job?.cancel() }
    fun send(available: Boolean) {
        val text = state.value.draft
        if (!available || state.value.busy || text.isBlank() || text.toByteArray().size > 65536) return
        val target = runtime.session.value ?: return
        fun checkTarget() { check(runtime.session.value == target) { "目标连接已改变" } }
        mutable.update { it.copy(busy = true, error = null, message = null) }
        job = scope.launch {
            var session: AdbSession? = null
            try {
                withContext(Dispatchers.IO) {
                    withTimeout(60_000) {
                        checkTarget()
                        session = connect()
                        checkTarget()
                        context.assets.open("scrcpy-server-v4.0").use { server ->
                            ScrcpyTextInput(checkNotNull(session).client).paste(server, text) { checkTarget() }
                        }
                    }
                }
                checkTarget()
                mutable.update { it.copy(draft = "", message = "电视已处理粘贴请求，请核对输入框内容。") }
            } catch (e: CancellationException) {
                mutable.update { it.copy(error = "输入中断，结果不确定，请核对电视后再发送。") }; throw e
            } catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "输入失败，请核对电视后重试。") } }
            finally { session?.close(); mutable.update { it.copy(busy = false) } }
        }
    }
}
