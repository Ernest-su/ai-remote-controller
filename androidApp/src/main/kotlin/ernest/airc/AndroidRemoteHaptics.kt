package ernest.airc

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

/** One vibrator shared by simultaneous keys; releasing one key must not stop another held key. */
internal class AndroidRemoteHaptics(private val context: Context) : RemoteHaptics {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var active = false
    fun setActive(value: Boolean) { active = value; if (!value) stop() }
    private val holds = mutableSetOf<Any>()
    private fun available() = active && vibrator?.hasVibrator() == true &&
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    override fun tap() {
        if (holds.isEmpty() && available()) vibrator?.vibrate(VibrationEffect.createOneShot(18, 90))
    }
    override fun startHold(key: Any) {
        if (!available() || !holds.add(key) || holds.size != 1) return
        val effect = if (vibrator?.hasAmplitudeControl() == true)
            VibrationEffect.createWaveform(longArrayOf(0, 1000), intArrayOf(0, 35), 0)
        else VibrationEffect.createWaveform(longArrayOf(0, 12, 68), 0)
        vibrator?.vibrate(effect)
    }
    override fun stopHold(key: Any) {
        if (holds.remove(key) && holds.isEmpty()) vibrator?.cancel()
    }
    fun stop() { holds.clear(); vibrator?.cancel() }
}
