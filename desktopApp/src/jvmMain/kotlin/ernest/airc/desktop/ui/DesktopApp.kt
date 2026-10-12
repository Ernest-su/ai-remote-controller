package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

class DesktopNavigation {
    var page by mutableStateOf(RemotePage.REMOTE)
    private var origin by mutableStateOf(RemotePage.REMOTE)
    fun navigate(next: RemotePage) { if (next == page) return; if (next == RemotePage.DEVICES) origin = page; page = next }
    fun back() { page = page.backDestination(origin) }
}

private val features = listOf(
    RemotePage.APPS to "ic_apps", RemotePage.TEXT to "ic_text", RemotePage.MODEL to "ic_model",
    RemotePage.SERVICE to "ic_service", RemotePage.PERMISSIONS to "ic_permissions", RemotePage.DEVICE_INFO to "ic_device_info",
    RemotePage.RESULTS to "ic_results", RemotePage.HELP to "ic_help", RemotePage.TOUCHPAD to "ic_touchpad", RemotePage.KEYPAD to "ic_keypad", RemotePage.SCREENSHOT to "ic_screenshot", RemotePage.FILES to "ic_files", RemotePage.MEDIA_CONTROL to "ic_media", RemotePage.GAMEPAD to "ic_gamepad", RemotePage.SETTINGS to "ic_settings",
)

@Composable
fun DesktopApp(controller: DesktopController, navigation: DesktopNavigation = remember { DesktopNavigation() },
    themeMode: ThemeMode = ThemeMode.SYSTEM, onThemeChange: (ThemeMode) -> Unit = {}) {
    val state by controller.state.collectAsState()
    val pages = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.feedback.revision) {
        if (state.feedback.revision > 0 && navigation.page != RemotePage.REMOTE) {
            when (state.feedback.phase) {
                FeedbackPhase.ERROR -> snackbar.showSnackbar(state.feedback.text.take(240))
                FeedbackPhase.COMPLETE -> if (navigation.page !in listOf(RemotePage.TOUCHPAD, RemotePage.KEYPAD, RemotePage.GAMEPAD, RemotePage.MEDIA_CONTROL)) snackbar.showSnackbar("已收到操作反馈")
                else -> Unit
            }
        }
    }
    RemoteTheme(darkTheme = themeMode.isDark(isSystemInDarkTheme())) {
        Box(Modifier.fillMaxSize().background(RemoteColors.Background), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 390.dp).fillMaxWidth().fillMaxHeight().testTag("desktop-app")) {
                Surface(color = RemoteColors.Header) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ if (navigation.page == RemotePage.REMOTE) navigation.navigate(RemotePage.MENU) else navigation.back() },
                            modifier = Modifier.size(48.dp).testTag(if (navigation.page == RemotePage.REMOTE) "open-menu" else "navigate-back")) {
                            Icon(painterResource("drawable/${if (navigation.page == RemotePage.REMOTE) "ic_menu" else "ic_back"}.xml"),
                                if (navigation.page == RemotePage.REMOTE) "打开功能菜单" else "返回", Modifier.size(24.dp))
                        }
                        Text(navigation.page.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton({ navigation.navigate(RemotePage.DEVICES) }, modifier = Modifier.heightIn(min = 48.dp).testTag("device-status")) {
                            Box(Modifier.size(7.dp).background(if (state.connected) RemoteColors.Brand else RemoteColors.Disabled, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.connected) "已连接" else "未连接", color = RemoteColors.Text)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    pages.SaveableStateProvider(navigation.page.name) {
                        when (navigation.page) {
                            RemotePage.REMOTE -> DesktopRemoteScreen(state, controller) { navigation.navigate(RemotePage.RESULTS) }
                            RemotePage.MENU, RemotePage.REMOTE_TYPES -> DesktopMenu(navigation)
                            RemotePage.DEVICES -> DesktopDevices(state, controller)
                            RemotePage.APPS -> DesktopApplications(state, controller)
                            RemotePage.TEXT -> DesktopTextCommands(state, controller)
                            RemotePage.MODEL -> ModelProfileEditor(controller.profiles, state.connected && !state.busy, state.modelConfigured, controller::syncProfile)
                            RemotePage.SERVICE -> DesktopServices(state, controller)
                            RemotePage.PERMISSIONS -> DesktopPermissions(state, controller)
                            RemotePage.DEVICE_INFO -> DesktopDeviceInfo(state, controller)
                            RemotePage.RESULTS -> PageContent(Modifier.testTag("results-page")) { ResultPanel(state.output) }
                            RemotePage.HELP -> DesktopHelp()
                            RemotePage.SETTINGS -> AppearanceSettings(themeMode, onThemeChange)
                            RemotePage.TOUCHPAD -> DesktopTouchpad(state, controller)
                            RemotePage.GAMEPAD -> DesktopGamepad(state, controller)
                            RemotePage.KEYPAD -> DesktopKeypad(state, controller)
                            RemotePage.SCREENSHOT -> DesktopScreenshot(state, controller)
                            RemotePage.MEDIA_CONTROL -> DesktopMediaControls(state, controller)
                            RemotePage.FILES -> DesktopFileBrowser(state, controller)
                        }
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DesktopMenu(navigation: DesktopNavigation) = PageContent(Modifier.testTag("feature-menu")) {
    Text("更多功能", style = MaterialTheme.typography.titleMedium)
    Surface(shape = MaterialTheme.shapes.small, color = RemoteColors.Surface) {
        Column {
            features.chunked(3).forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = RemoteColors.Divider, thickness = 0.5.dp)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    row.forEachIndexed { column, (page, icon) ->
                        if (column > 0) Box(Modifier.width(0.5.dp).fillMaxHeight().background(RemoteColors.Divider))
                        Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 104.dp).testTag("feature-${page.name.lowercase()}")
                            .clickable(role = Role.Button) { navigation.navigate(page) }.padding(horizontal = 8.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(painterResource("drawable/$icon.xml"), null, Modifier.size(28.dp), tint = RemoteColors.Action)
                            Text(page.title, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
