package ernest.airc

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope

/** One vibrator shared by simultaneous keys; releasing one key must not stop another held key. */
internal class AndroidRemoteHaptics(private val context: Context, scope: CoroutineScope) : RemoteHaptics {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val amplitudeControl = vibrator?.hasAmplitudeControl() == true
    private var active = false
    private val playback = RemoteHapticPlayback(scope, ::available, amplitudeControl,
        pulse = { duration, amplitude ->
            vibrator?.vibrate(VibrationEffect.createOneShot(duration,
                if (amplitudeControl) amplitude else VibrationEffect.DEFAULT_AMPLITUDE))
        }, cancel = { vibrator?.cancel() })
    fun setActive(value: Boolean) { active = value; if (!value) playback.stop() }
    private fun available() = active && vibrator?.hasVibrator() == true &&
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    override fun trace(event:String) { if(BuildConfig.DEBUG) android.util.Log.i("AircHaptics",event) }
    override fun tap() { trace("tap");playback.tap() }
    override fun startHold(key: Any) { trace("hold-start ${System.identityHashCode(key)}");playback.startHold(key) }
    override fun stopHold(key: Any) { trace("hold-stop ${System.identityHashCode(key)}");playback.stopHold(key) }
}
