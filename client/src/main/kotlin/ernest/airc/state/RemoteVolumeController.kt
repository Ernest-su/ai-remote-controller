package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

/** Binds polling and writes to one runtime session; never infers volume from key presses. */
class RemoteVolumeController(private val scope: CoroutineScope, private val runtime: RuntimeConnection,
    private val onError: (String) -> Unit) {
    private val mutable = MutableStateFlow<RemoteVolume?>(null)
    val state = mutable.asStateFlow()
    private val mutableSetting = MutableStateFlow(false)
    val setting = mutableSetting.asStateFlow()
    private val mutex = Mutex()
    private var snapshotTarget: RuntimeTransferTarget? = null
    init {
        scope.launch {
            runtime.session.collectLatest { session ->
                mutable.value = null
                snapshotTarget = null
                mutableSetting.value = false
                if (session != null) {
                    // A session is published immediately before connected=true. An immediate
                    // collector must wait rather than permanently abandon this session.
                    runtime.connected.first { it }
                    val target = runCatching { runtime.transferTarget() }.getOrNull() ?: return@collectLatest
                    while (true) {
                        mutex.withLock {
                            try {
                                val output = runtime.requestAt(target, "media.volume", buildJsonObject {})
                                runtime.checkTransferTarget(target)
                                snapshotTarget = target
                                mutable.value = parseRemoteVolume(output)
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { mutable.value = null }
                        }
                        delay(1_000)
                    }
                }
            }
        }
    }
    fun setFraction(fraction: Float) {
        if (state.value == null) return
        if (!fraction.isFinite() || runtime.session.value == null || setting.value) return
        val target = snapshotTarget ?: return
        val percent = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
        mutableSetting.value = true
        scope.launch {
            mutex.withLock {
                try {
                    runtime.checkTransferTarget(target)
                    val output = runtime.requestAt(target, "media.volume", buildJsonObject {
                        put("percent", percent)
                    })
                    runtime.checkTransferTarget(target)
                    mutable.value = checkNotNull(parseRemoteVolume(output)) { "目标未报告有效媒体音量" }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (runCatching { runtime.checkTransferTarget(target) }.isSuccess) onError(e.message ?: "音量调节失败")
                } finally {
                    if (runCatching { runtime.checkTransferTarget(target) }.isSuccess) mutableSetting.value = false
                }
            }
        }
    }
}
