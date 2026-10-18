package ernest.airc

import android.content.Context
import ernest.ascrcpy.scrcpy.ScrcpyTextInput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class TvInputMode(val title: String) { PASTE("剪贴板粘贴"), IME("输入法提交");
    companion object { fun stored(value: String?) = entries.firstOrNull { it.name == value } ?: PASTE }
}

internal interface TvInputBackend : AutoCloseable {
    suspend fun status(): TvImeStatus
    suspend fun enable(): TvImeStatus
    suspend fun restore(): TvImeStatus
    suspend fun commit(text: String): TvImeStatus
    suspend fun paste(text: String)
}

data class TvInputState(val draft: String = "", val busy: Boolean = false, val message: String? = null,
    val error: String? = null, val mode: TvInputMode = TvInputMode.PASTE, val progress: String? = null,
    internal val ime: TvImeStatus? = null)

/** Draft stays in ViewModel memory; only each television's input mode is persisted. */
class TvInputController internal constructor(private val scope: CoroutineScope,
    private val target: () -> Any?, private val deviceId: () -> String?,
    private val readMode: (String) -> TvInputMode, private val saveMode: (String, TvInputMode) -> Unit,
    private val connectBackend: suspend (() -> Unit) -> TvInputBackend) {
    constructor(scope: CoroutineScope, context: Context, runtime: RuntimeConnection,
        deviceId: () -> String?, connect: suspend () -> AdbSession) : this(scope, { runtime.session.value }, deviceId,
        { TvInputMode.stored(context.getSharedPreferences("tv-input", 0).getString(it, null)) },
        { id, mode -> context.getSharedPreferences("tv-input", 0).edit().putString(id, mode.name).apply() },
        { checkTarget ->
            val session = connect()
            try {
                checkTarget()
                val ime = TvInputMethodTransport(context, session.client, checkTarget)
                object : TvInputBackend {
                    override suspend fun status() = ime.status()
                    override suspend fun enable() = ime.enable()
                    override suspend fun restore() = ime.restore()
                    override suspend fun commit(text: String) = ime.commit(text)
                    override suspend fun paste(text: String) {
                        context.assets.open("scrcpy-server-v4.0").use {
                            ScrcpyTextInput(session.client).paste(it, text, checkTarget)
                        }
                    }
                    override fun close() = session.close()
                }
            } catch (e: Exception) { session.close(); throw e }
        })
    private val mutable = MutableStateFlow(TvInputState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var boundId: String? = null
    @Volatile private var generation = 0L
    fun selectTarget(id: String?) {
        if (id == boundId) return
        generation++; job?.cancel(); boundId = id
        mutable.update { TvInputState(draft = it.draft, mode = id?.let(readMode) ?: TvInputMode.PASTE) }
    }
    fun mode(value: TvInputMode) {
        selectTarget(deviceId())
        if (state.value.busy) return
        boundId?.let { saveMode(it, value) }
        mutable.update { it.copy(mode = value, error = null, message = null) }
    }
    fun edit(value: String) { if (!state.value.busy) mutable.update { it.copy(draft = value, message = null, error = null) } }
    fun cancel() { job?.cancel(); mutable.update { it.copy(ime = null) } }
    fun refresh(available: Boolean) = run(available, "正在检查电视输入法…", { it.status() }) { status ->
        mutable.update { it.copy(ime = status) }
    }
    fun enable(available: Boolean) = run(available, "正在安装并启用输入法…", { it.enable() }) { status ->
        mutable.update { it.copy(ime = status, message = "AIRC 输入法已启用，请在电视选中输入框。") }
    }
    fun restore(available: Boolean) = run(available, "正在恢复原输入法…", { it.restore() }) { status ->
        boundId?.let { saveMode(it, TvInputMode.PASTE) }
        mutable.update { it.copy(ime = status, mode = TvInputMode.PASTE, message = "已恢复电视原输入法，当前使用剪贴板粘贴。") }
    }
    fun send(available: Boolean) {
        selectTarget(deviceId())
        val snapshot = state.value
        if (snapshot.draft.isBlank() || snapshot.draft.toByteArray().size > 65536 ||
            (snapshot.mode == TvInputMode.IME && snapshot.ime?.let { it.aligned && it.selected } != true)) return
        run(available, if (snapshot.mode == TvInputMode.IME) "正在提交文字…" else "正在发送粘贴请求…", { backend ->
            if (snapshot.mode == TvInputMode.IME) backend.commit(snapshot.draft)
            else { backend.paste(snapshot.draft); null }
        }) { status ->
            mutable.update { it.copy(ime = status ?: it.ime, draft = "", message = if (snapshot.mode == TvInputMode.IME)
                "输入法已提交文字，请核对电视输入框。" else "电视已处理粘贴请求，请核对输入框内容。") }
        }
    }
    private fun <T> run(available: Boolean, progress: String, action: suspend (TvInputBackend) -> T, complete: (T) -> Unit) {
        selectTarget(deviceId())
        if (!available || state.value.busy) return
        val connection = target() ?: return
        val id = boundId ?: return
        val revision = generation
        fun checkTarget() {
            check(target() == connection && deviceId() == id && revision == generation) { "目标连接已改变，请重新操作。" }
        }
        mutable.update { it.copy(busy = true, progress = progress, error = null, message = null) }
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    withTimeout(60_000) {
                        checkTarget()
                        connectBackend(::checkTarget).use { backend ->
                            checkTarget()
                            // Backend methods check again before and after every remote operation.
                            action(backend)
                        }
                    }
                }
                checkTarget()
                complete(result)
            } catch (e: CancellationException) {
                if (revision == generation) mutable.update { it.copy(ime = null, error = "操作已中断，结果不确定，请核对电视后再操作。") }
                throw e
            } catch (e: Exception) {
                if (revision == generation) mutable.update { it.copy(ime = null, error = e.message ?: "操作失败，请核对电视后重试。") }
            } finally {
                if (revision == generation) mutable.update { it.copy(busy = false, progress = null) }
            }
        }
    }
}
