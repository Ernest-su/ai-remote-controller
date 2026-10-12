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
    private lateinit var model: RemoteViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[RemoteViewModel::class.java]
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
    override fun onStart() { super.onStart(); model.startDiscovery() }
    override fun onStop() { model.stopDiscovery(); super.onStop() }
}
