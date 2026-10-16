package ernest.airc

import kotlinx.coroutines.*

/** Bounded pulses prevent a delayed device cancellation from leaving an endless waveform running. */
internal class RemoteHapticPlayback(
    private val scope: CoroutineScope,
    private val available: () -> Boolean,
    private val amplitudeControl: Boolean,
    private val pulse: (Long, Int) -> Unit,
    private val cancel: () -> Unit,
) : RemoteHaptics {
    private val holds = mutableSetOf<Any>()
    private var holdJob: Job? = null

    override fun tap() {
        if (holds.isEmpty() && available()) pulse(35, 210)
    }
    override fun startHold(key: Any) {
        if (!available() || !holds.add(key) || holds.size != 1) return
        holdJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive && available()) {
                pulse(if (amplitudeControl) 24 else 12, 50)
                delay(40)
            }
        }
    }
    override fun stopHold(key: Any) {
        if (holds.remove(key) && holds.isEmpty()) stop()
    }
    fun stop() {
        holds.clear()
        holdJob?.cancel()
        holdJob = null
        cancel()
    }
}
