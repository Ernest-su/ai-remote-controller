package ernest.airc.desktop

import androidx.compose.runtime.*
import ernest.airc.ThemeMode
import java.util.prefs.Preferences
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.awt.Robot
import javax.imageio.ImageIO
import java.io.File

fun main() = application {
    val appearance = remember { Preferences.userRoot().node("ernest/airc/appearance") }
    var themeMode by remember { mutableStateOf(ThemeMode.fromStored(appearance.get("theme", null))) }
    val controller = remember { DesktopController() }
    val navigation = remember { DesktopNavigation() }
    DisposableEffect(controller) { onDispose { controller.close() } }
    Window(onCloseRequest = ::exitApplication, title = "AIRC · 桌面测试工具",
        state = rememberWindowState(width = 390.dp, height = 844.dp),
        onPreviewKeyEvent = { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) { navigation.back(); true } else false
        }) {
        DesktopApp(controller, navigation, themeMode) { selected ->
            appearance.put("theme", selected.name)
            themeMode = selected
        }
        LaunchedEffect(Unit) {
            val output = System.getenv("AIRC_DESKTOP_SCREENSHOT")
            if (!output.isNullOrBlank()) {
                delay(2000)
                val bounds = window.bounds
                withContext(Dispatchers.IO) { ImageIO.write(Robot().createScreenCapture(bounds), "png", File(output)) }
                exitApplication()
            }
        }
    }
}
