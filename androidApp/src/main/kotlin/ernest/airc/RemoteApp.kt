package ernest.airc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun RemoteApp(model: RemoteViewModel, themeMode: ThemeMode = ThemeMode.SYSTEM, onThemeChange: (ThemeMode) -> Unit = {}) {
    val state by model.state.collectAsState()
    val gamepad by model.virtualGamepad.state.collectAsState()
    val gamepadEnabled = gamepadAvailable(state.connected, state.tools)
    val shared by model.incomingShare.collectAsState()
    var shareOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(shared?.id) { if (shared != null) shareOpen = true }
    var sharedDownload by rememberSaveable { mutableStateOf(false) }
    var routinesOpen by rememberSaveable { mutableStateOf(false) }
    if (routinesOpen) QuickRoutinesPanel(state, model) { routinesOpen = false }
    var focusInputRequested by remember { mutableStateOf(false) }
    var tvInput by rememberSaveable { mutableStateOf(false) }
    if (tvInput) ManagementDialog("电视输入", "tv-input-dialog", { tvInput = false; focusInputRequested = false }, grouped = true) {
        TvInputPanel(state, model, requestEditorFocus = focusInputRequested)
    }
    var layoutEditor by rememberSaveable { mutableStateOf(false) }
    if (layoutEditor) RemoteLayoutEditor(state, model) { layoutEditor = false }
    var quickApps by rememberSaveable { mutableStateOf(false) }
    if (quickApps) LibraryDialog("常用应用", "quick-apps-dialog", { quickApps = false }, subtitle = state.history.find { it.id == state.selected }?.visibleName) { AppShortcutsPanel(state, model) }
    val launcherDraft by model.launcherDraft.collectAsState()
    launcherDraft?.let { LauncherShortcutNameDialog(it, model::confirmLauncher, model::dismissLauncher) }
    var appDetailsPackage by rememberSaveable { mutableStateOf("") }
    var pageName by rememberSaveable { mutableStateOf(RemotePage.REMOTE.name) }
    var deviceOriginName by rememberSaveable { mutableStateOf(RemotePage.REMOTE.name) }
    val page = RemotePage.valueOf(pageName)
    val deviceOrigin = RemotePage.valueOf(deviceOriginName)
    val pages = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    var lastGamepadError by rememberSaveable { mutableStateOf(gamepad.error) }
    LaunchedEffect(gamepad.error) {
        val error = gamepad.error
        val fresh = error != lastGamepadError
        lastGamepadError = error
        if (page == RemotePage.GAMEPAD && fresh) error?.let { snackbar.showSnackbar(it.take(240)) }
    }
    var lastNoticeRevision by remember { mutableLongStateOf(state.feedback.revision) }
    LaunchedEffect(page) { snackbar.currentSnackbarData?.dismiss() }
    LaunchedEffect(state.feedback.revision) {
        val inlineText = when (page) {
            RemotePage.REMOTE, RemotePage.TEXT -> state.feedback.text
            RemotePage.DEVICES -> state.status
            RemotePage.RESULTS -> state.output
            else -> null
        }
        val message = state.feedback.snackbarMessage(lastNoticeRevision, inlineText)
        lastNoticeRevision = state.feedback.revision
        message?.let { snackbar.showSnackbar(it.take(240)) }
    }
    var selectedTypeName by rememberSaveable { mutableStateOf(RemotePage.REMOTE.name) }
    var mirrorFullscreen by rememberSaveable { mutableStateOf(false) }
    val isRemoteType = remoteTypes.any { it.page == page }
    fun navigate(target: RemotePage) {
        if (target == page) return
        // A fresh visit receives its own preview and floating-remote navigation state.
        if (page == RemotePage.SCREENSHOT) { pages.removeState(RemotePage.SCREENSHOT.name); mirrorFullscreen = false }
        if (target == RemotePage.DEVICES) deviceOriginName = page.name
        if (remoteTypes.any { it.page == target }) selectedTypeName = target.name
        model.hardwareRemoteActive(remoteTypes.any { it.page == target } || target == RemotePage.SCREENSHOT)
        pageName = target.name
    }
    if (shareOpen) shared?.let { share ->
        ManagementDialog("分享至电视", "incoming-share-dialog", { shareOpen = false }) {
            SharePanel(share, state, model,
                { shareOpen = false; navigate(RemotePage.DEVICES) },
                { shareOpen = false; sharedDownload = true },
                { shareOpen = false; tvInput = true })
            TextButton({ model.discardShare(); shareOpen = false }) { Text("移除分享内容") }
        }
    }
    if (sharedDownload) ManagementDialog("下载到电视", "shared-download-dialog", { sharedDownload = false }) { DownloadPanel(state, model) }
    fun back() { navigate(page.androidBackDestination(deviceOrigin)) }
    BackHandler(enabled = page != RemotePage.REMOTE) { back() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val remoteActive = isRemoteType || page == RemotePage.SCREENSHOT
    val focusedEditor by model.tvInput.focusedEditor.collectAsState()
    LaunchedEffect(remoteActive, state.connected, state.selected, lifecycle) {
        if (remoteActive && state.connected) lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                model.tvInput.monitorFocus()
                delay(1000)
            }
        }
    }
    DisposableEffect(remoteActive, model) {
        model.hardwareRemoteActive(remoteActive)
        onDispose { model.hardwareRemoteActive(false) }
    }
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view, lifecycle, remoteActive, state.keepAwake, state.connected) {
        val old = view.keepScreenOn
        fun update() { view.keepScreenOn = old || (remoteActive && state.keepAwake && state.connected && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); view.keepScreenOn = old }
    }
    val hapticScope = rememberCoroutineScope()
    val haptics = remember(context, hapticScope) { AndroidRemoteHaptics(context, hapticScope) }
    DisposableEffect(haptics, lifecycle, state.keyHapticsEnabled, page) {
        haptics.setActive(state.keyHapticsEnabled && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) haptics.setActive(state.keyHapticsEnabled)
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) haptics.setActive(false)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); haptics.setActive(false) }
    }
    CompositionLocalProvider(LocalRemoteHaptics provides if (state.keyHapticsEnabled) haptics else LocalRemoteHaptics.current) {
    Column(Modifier.fillMaxSize().background(RemoteColors.Background).safeDrawingPadding()) {
        if (!mirrorFullscreen) Surface(color = RemoteColors.Header) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { if (page == RemotePage.REMOTE) navigate(RemotePage.MENU) else back() },
                    modifier = Modifier.size(48.dp).testTag(if (page == RemotePage.REMOTE) "open-menu" else "navigate-back"),
                ) {
                    Icon(painterResource(if (page == RemotePage.REMOTE) R.drawable.ic_menu else R.drawable.ic_back),
                        if (page == RemotePage.REMOTE) "打开功能菜单" else "返回", Modifier.size(24.dp))
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(page.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (page == RemotePage.GAMEPAD) {
                        GamepadSessionButton(gamepad, gamepadEnabled,
                            { model.virtualGamepad.start(gamepadEnabled, allowSynthetic = true) }, { model.virtualGamepad.stop() })
                        GamepadTypeSelector(gamepad, model.virtualGamepad::selectType)
                    }
                }
                if (shared != null) IconButton({ shareOpen = true }, Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_files), "待处理的分享", Modifier.size(24.dp))
                }
                if (isRemoteType) IconButton({ navigate(RemotePage.REMOTE_TYPES) }, Modifier.size(48.dp).testTag("open-remote-types")) {
                    Icon(painterResource(R.drawable.ic_remote_types), "切换遥控器类型", Modifier.size(24.dp))
                }
                Row(
                    Modifier.heightIn(min = 48.dp).testTag("device-status")
                        .clickable(role = Role.Button, onClickLabel = "管理设备") { navigate(RemotePage.DEVICES) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(7.dp).background(if (state.connected) RemoteColors.Brand else RemoteColors.Disabled, CircleShape))
                    Text(if (state.connected) "已连接" else "未连接", style = MaterialTheme.typography.bodyMedium)
                }
                if (page == RemotePage.REMOTE) RemoteQuickMenu({ quickApps = true }, { tvInput = true }, { routinesOpen = true })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = if (mirrorFullscreen) androidx.compose.ui.unit.Dp.Infinity else if (page == RemotePage.GAMEPAD) RemoteDimensions.GamepadContentWidth else 560.dp).fillMaxWidth()) {
                pages.SaveableStateProvider(page.name) {
                    when (page) {
                        RemotePage.REMOTE_TYPES -> RemoteTypePicker(RemotePage.valueOf(selectedTypeName), ::navigate)
                        RemotePage.REMOTE -> RemoteControls(state, model) { navigate(RemotePage.RESULTS) }
                        RemotePage.DEVICES -> Devices(state, model) { navigate(RemotePage.SCREENSHOT) }
                        RemotePage.MENU -> FeatureMenu(::navigate)
                        RemotePage.APPS -> Applications(state, model) { name -> appDetailsPackage = name; navigate(RemotePage.APP_DETAILS) }
                        RemotePage.APP_DETAILS -> ApplicationDetails(appDetailsPackage, state, model) { navigate(RemotePage.APPS) }
                        RemotePage.TEXT -> TextCommands(state, model)
                        RemotePage.MODEL -> ModelSettings(state, model)
                        RemotePage.SERVICE -> ServiceSettings(state, model)
                        RemotePage.PERMISSIONS -> PermissionSettings(state, model)
                        RemotePage.DEVICE_INFO -> DeviceInformation(state, model)
                        RemotePage.RESULTS -> PageContent(Modifier.testTag("results-page")) { ResultPanel(state.output) }
                        RemotePage.HELP -> HelpScreen()
                        RemotePage.SETTINGS -> AppearanceSettings(themeMode, onThemeChange) {
                            OutlinedButton({ layoutEditor = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("自定义此电视的遥控按键") }
                            RemoteComfortSettings(state, model)
                            FloatingRemoteSettings(state.floatingRemoteTransparency, model::setFloatingRemoteTransparency)
                            Panel {
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("remote-key-haptics")
                                    .toggleable(value = state.keyHapticsEnabled, role = Role.Switch, onValueChange = model::setKeyHapticsEnabled)
                                    .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("遥控按键震动反馈", style = MaterialTheme.typography.bodyLarge)
                                        Text("点按短震，长按持续弱震", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
                                    }
                                    Switch(checked = state.keyHapticsEnabled, onCheckedChange = null)
                                }
                            }
                        }
                        RemotePage.TOUCHPAD -> TouchpadScreen(state, model)
                        RemotePage.GAMEPAD -> GamepadScreen(state, model)
                        RemotePage.KEYPAD -> KeypadScreen(state, model)
                        RemotePage.SCREENSHOT -> ScreenshotScreen(state, model, mirrorFullscreen) { mirrorFullscreen = it }
                        RemotePage.MEDIA_CONTROL -> MediaControls(state, model)
                        RemotePage.SCHEDULES -> {
                            val scheduleState by model.schedules.state.collectAsState()
                            SchedulePanel(state.connected, scheduleState, state.apps, model::listApps, model.schedules::refresh, model.schedules::save, model.schedules::delete, model.schedules::enable, state.history.find { it.id == state.selected }?.visibleName ?: "当前被控设备")
                        }
                        RemotePage.FILES -> FileBrowserScreen(state, model)
                    }
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = RemoteColors.Brand)
            TvInputFocusPrompt(remoteActive && state.connected && focusedEditor != null && !tvInput && !quickApps && !routinesOpen && !shareOpen && !sharedDownload,
                { focusInputRequested = true; tvInput = true }, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
    }
}

@Composable
private fun FeatureMenu(navigate: (RemotePage) -> Unit) {
    PageContent(Modifier.testTag("feature-menu")) {
        Text("设备与应用", style = MaterialTheme.typography.titleMedium)
        Text("管理应用、配置模型与个性化外观", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        Surface(shape = MaterialTheme.shapes.small, color = RemoteColors.Surface) {
            Column {
                remoteFeatures.chunked(3).forEachIndexed { rowIndex, row ->
                    if (rowIndex > 0) HorizontalDivider(color = RemoteColors.Divider, thickness = 0.5.dp)
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                        row.forEachIndexed { columnIndex, feature ->
                            if (columnIndex > 0) Box(Modifier.width(0.5.dp).fillMaxHeight().background(RemoteColors.Divider))
                            Column(
                                Modifier.weight(1f).fillMaxHeight().heightIn(min = 104.dp).testTag("feature-${feature.page.name.lowercase()}")
                                    .clickable(role = Role.Button) { navigate(feature.page) }.padding(horizontal = 8.dp, vertical = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(painterResource(feature.icon), null, Modifier.size(28.dp), tint = RemoteColors.Action)
                                Text(feature.page.title, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                            }
                        }
                        repeat(3 - row.size) {
                            Box(Modifier.width(0.5.dp).fillMaxHeight().background(RemoteColors.Divider))
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteControls(state: RemoteUiState, model: RemoteViewModel, showResults: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        if (model.text(draft)) {
            draft = ""
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    var feedbackVisible by remember { mutableStateOf(state.feedback.remainingVisibleMillis() > 0L) }
    LaunchedEffect(state.feedback.revision) {
        val remaining = state.feedback.remainingVisibleMillis()
        feedbackVisible = remaining > 0L
        if (feedbackVisible) {
            delay(remaining)
            feedbackVisible = false
        }
    }
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(1f)
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    RemoteKeyLifecycle(model.remoteKeys)
    CompositionLocalProvider(LocalRemoteKeySender provides model.remoteKeys) {
    Box(Modifier.fillMaxSize().testTag("remote-page").padding(
        start = RemoteDimensions.OuterPadding, end = RemoteDimensions.OuterPadding,
        top = RemoteDimensions.OuterPadding, bottom = if (imeVisible) 0.dp else RemoteDimensions.OuterPadding,
    ), contentAlignment = Alignment.TopCenter) {
        Surface(shape = MaterialTheme.shapes.small, color = RemoteColors.Fill,
            modifier = Modifier.widthIn(max = RemoteDimensions.Width).fillMaxWidth().fillMaxHeight()) {
            Column(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val panelHeight = maxOf(maxHeight, RemoteDimensions.MinimumControlsHeight * fontScale)
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Column(
                            Modifier.height(panelHeight).fillMaxWidth().padding(
                                start = RemoteDimensions.InnerPadding, end = RemoteDimensions.InnerPadding,
                                top = RemoteDimensions.VerticalPadding, bottom = 8.dp,
                            ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                RemoteIconButton({ model.key("power") }, enabled = state.connected, debugName = "power",
                                    modifier = Modifier.size(48.dp).then(if (LocalMirrorOverlay.current) Modifier else Modifier.background(RemoteColors.Surface, CircleShape))) {
                                    Icon(painterResource(R.drawable.ic_power), "电源", Modifier.size(24.dp),
                                        tint = if (state.connected) RemoteColors.Warning else RemoteColors.Disabled)
                                }
                            }
                            val volume by model.remoteVolume.state.collectAsState()
                            val volumeSetting by model.remoteVolume.setting.collectAsState()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                                RemoteIconButton({ model.key("mute") }, enabled = state.connected, debugName = "mute") { Icon(painterResource(R.drawable.ic_mute), "静音") }
                                RemoteIconButton({ model.key("volume_down") }, enabled = state.connected, debugName = "volume_down") { Icon(painterResource(R.drawable.ic_volume_down), "音量 −") }
                                RemoteVolumeSlider(volume, state.connected, model.remoteVolume::setFraction, Modifier.weight(1f), setting = volumeSetting)
                                RemoteIconButton({ model.key("volume_up") }, enabled = state.connected, debugName = "volume_up") { Icon(painterResource(R.drawable.ic_volume_up), "音量 +") }
                            }
                            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    Surface(shape = MaterialTheme.shapes.small, color = RemoteColors.Surface,
                                        modifier = Modifier.fillMaxWidth().testTag("remote-status")) {
                                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (feedbackVisible) {
                                                Text(state.feedback.phase.label, style = MaterialTheme.typography.bodySmall,
                                                    color = if (state.feedback.phase == FeedbackPhase.ERROR) RemoteColors.Warning else RemoteColors.Secondary)
                                                Text(state.feedback.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("remote-feedback"))
                                                TextButton(showResults, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看执行结果", style = MaterialTheme.typography.bodySmall) }
                                            } else {
                                                Text("播放状态", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                                                Text(state.mediaStatus, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("remote-playback"))
                                            }
                                        }
                                    }
                                }
                                if (!feedbackVisible) {
                                    Row(Modifier.fillMaxWidth().testTag("remote-media-controls"), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        RemoteKey(R.drawable.ic_previous, "上一曲", state.connected, size = 52.dp, key = "media_previous") { model.key("media_previous") }
                                        RemoteKey(R.drawable.ic_play_pause, "播放 / 暂停", state.connected, size = 52.dp, key = "play_pause") { model.key("play_pause") }
                                        RemoteKey(R.drawable.ic_next, "下一曲", state.connected, size = 52.dp, key = "media_next") { model.key("media_next") }
                                    }
                                }
                            }
                            Box(Modifier.size(RemoteDimensions.Dpad).testTag("remote-dpad").background(RemoteColors.Surface, CircleShape).border(0.5.dp, RemoteColors.Divider, CircleShape)) {
                                @Composable fun Direction(icon: Int, label: String, key: String, alignment: Alignment, padding: PaddingValues) {
                                    RemoteIconButton({ model.key(key) }, enabled = state.connected, modifier = Modifier.align(alignment).padding(padding).size(60.dp), debugName = key, overlayBacking = false) {
                                        Icon(painterResource(icon), label, Modifier.size(28.dp))
                                    }
                                }
                                Direction(R.drawable.ic_up, "向上", "up", Alignment.TopCenter, PaddingValues(top = 8.dp))
                                Direction(R.drawable.ic_down, "向下", "down", Alignment.BottomCenter, PaddingValues(bottom = 8.dp))
                                Direction(R.drawable.ic_left, "向左", "left", Alignment.CenterStart, PaddingValues(start = 8.dp))
                                Direction(R.drawable.ic_right, "向右", "right", Alignment.CenterEnd, PaddingValues(end = 8.dp))
                                Box(Modifier.size(RemoteDimensions.Confirm).align(Alignment.Center)
                                    .background(if (LocalMirrorOverlay.current) RemoteColors.Fill else if (state.connected) RemoteColors.Action else RemoteColors.Disabled, CircleShape)
                                    .then(if (LocalMirrorOverlay.current) Modifier.border(0.5.dp, RemoteColors.Divider, CircleShape) else Modifier)
                                    .remoteKeyInput("ok", state.connected) { model.key("ok") }, contentAlignment = Alignment.Center) {
                                    Text("OK", fontSize = 20.sp, color = if (LocalMirrorOverlay.current && !state.connected) RemoteColors.Disabled else RemoteColors.OnAction)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                RemoteKey(R.drawable.ic_back, "返回", state.connected, size = 58.dp, key = "back") { model.key("back") }
                                RemoteKey(R.drawable.ic_home, "主页", state.connected, size = 58.dp, key = "home") { model.key("home") }
                                RemoteKey(R.drawable.ic_device_menu, "设备菜单", state.connected, size = 58.dp, key = "menu") { model.key("menu") }
                            }
                        }
                        if (state.remoteLayout.shortcuts.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(RemoteDimensions.InnerPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CustomRemoteKeys(state, model)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().mirrorControlBacking(MaterialTheme.shapes.small).padding(start = RemoteDimensions.InnerPadding, end = RemoteDimensions.InnerPadding, bottom = if (imeVisible) 8.dp else RemoteDimensions.VerticalPadding).testTag("remote-text-controls"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteModelAvailability(state.modelConfigured) {
                        OutlinedTextField(
                            value = draft, onValueChange = { draft = it },
                            modifier = Modifier.weight(1f).testTag("remote-command-input"),
                            placeholder = { Text("输入文字指令", style = MaterialTheme.typography.bodyMedium) },
                            minLines = 1, maxLines = 3,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                        )
                        FilledIconButton(onClick = ::submit,
                            enabled = state.connected && state.modelConfigured && !state.submitting && draft.isNotBlank(),
                            modifier = Modifier.size(48.dp).testTag("remote-send-command"),
                            colors = if (LocalMirrorOverlay.current) IconButtonDefaults.filledIconButtonColors(
                                containerColor = RemoteColors.Surface, contentColor = RemoteColors.Text,
                                disabledContainerColor = RemoteColors.Surface, disabledContentColor = RemoteColors.Disabled,
                            ) else IconButtonDefaults.filledIconButtonColors()) {
                            Icon(painterResource(R.drawable.ic_send), "发送文字指令", Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun RemoteKey(icon: Int, label: String, enabled: Boolean, tint: Color = RemoteColors.Text, size: androidx.compose.ui.unit.Dp = 48.dp, key: String = label, action: () -> Unit) {
    Column(
        Modifier.widthIn(min = 64.dp).remoteKeyInput(key, enabled, action)
            .semantics(mergeDescendants = true) {}.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(size).then(if (LocalMirrorOverlay.current) Modifier.mirrorControlBacking()
            else Modifier.background(RemoteColors.Surface, CircleShape)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(24.dp), tint = if (enabled) tint else RemoteColors.Disabled)
        }
        Text(label, modifier = Modifier.mirrorControlBacking(MaterialTheme.shapes.small)
            .then(if (LocalMirrorOverlay.current) Modifier.padding(horizontal = 4.dp) else Modifier),
            style = MaterialTheme.typography.bodySmall, color = if (enabled) RemoteColors.Text else RemoteColors.Disabled)
    }
}

@Composable
private fun RemoteTypePicker(selected: RemotePage, navigate: (RemotePage) -> Unit) {
    PageContent(Modifier.testTag("remote-types-page")) {
        Text("选择适合当前操作的遥控器", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
            Column {
                remoteTypes.forEachIndexed { index, type ->
                    if (index > 0) HorizontalDivider(color = RemoteColors.Divider, thickness = 0.5.dp)
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("remote-type-${type.page.name.lowercase()}")
                        .clickable(role = Role.Button) { navigate(type.page) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(painterResource(type.icon), null, Modifier.size(24.dp), tint = RemoteColors.Action)
                        Text(if (type.page == RemotePage.REMOTE) "标准遥控器" else type.page.title,
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (type.page == selected) Text("当前", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Action)
                        Icon(painterResource(R.drawable.ic_right), null, Modifier.size(24.dp), tint = RemoteColors.Secondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteIconButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier, debugName: String = "remote-key", overlayBacking: Boolean = true, content: @Composable () -> Unit) {
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .then(if (overlayBacking) Modifier.mirrorControlBacking() else Modifier).remoteKeyInput(debugName, enabled, onClick),
        contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) RemoteColors.Text else RemoteColors.Disabled) { content() }
    }
}

@Composable
internal fun RemoteKeyLifecycle(sender: RemoteKeySender) {
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (!focused) sender.cancelAll() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, sender) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) sender.cancelAll()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); sender.cancelAll() }
    }
}
