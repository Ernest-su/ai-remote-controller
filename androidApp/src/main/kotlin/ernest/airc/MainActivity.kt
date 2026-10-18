package ernest.airc

import android.os.Bundle
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (BuildConfig.DEBUG && event.actionMasked != android.view.MotionEvent.ACTION_MOVE) {
            android.util.Log.i("AircHaptics", "input action=${event.actionMasked} pointers=${event.pointerCount} down=${event.downTime} event=${event.eventTime} lag=${android.os.SystemClock.uptimeMillis() - event.eventTime}")
        }
        return super.dispatchTouchEvent(event)
    }
    private lateinit var model: RemoteViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[RemoteViewModel::class.java]
        if (savedInstanceState == null) model.receiveShare(intent)
        setContent {
            val preferences = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
            var mode by remember { mutableStateOf(ThemeMode.fromStored(preferences.getString("theme", null))) }
            DisposableEffect(preferences) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                    if (key == "theme") mode = ThemeMode.fromStored(prefs.getString("theme", null))
                }
                preferences.registerOnSharedPreferenceChangeListener(listener)
                onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            val dark = mode.isDark(isSystemInDarkTheme())
            val palette = if (dark) DarkRemotePalette else LightRemotePalette
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(palette.Header.toArgb())
                        else SystemBarStyle.light(palette.Header.toArgb(), palette.Header.toArgb()),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(palette.Background.toArgb())
                        else SystemBarStyle.light(palette.Background.toArgb(), palette.Background.toArgb()),
                )
            }
            RemoteTheme(darkTheme = dark) {
                RemoteApp(model, mode) { selected ->
                    mode = selected
                    preferences.edit().putString("theme", selected.name).apply()
                }
            }
        }
    }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        if (::model.isInitialized && model.hardwareVolume(event)) true else super.dispatchKeyEvent(event)
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::model.isInitialized) model.releaseHardwareKeys()
    }
    override fun onPause() {
        model.releaseHardwareKeys(); model.tvInput.cancel()
        super.onPause()
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.receiveShare(intent)
    }
    override fun onStart() { super.onStart(); model.startDiscovery() }
    override fun onStop() {
        if (!isChangingConfigurations) model.quickRoutines.cancel()
        model.stopDiscovery()
        super.onStop()
    }
}
