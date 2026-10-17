package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode(val title: String) {
    SYSTEM("跟随系统"), LIGHT("亮色"), DARK("暗色");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/** Semantic palette shared by both platforms. See development/visual-guidelines.md. */
data class RemotePalette(
    val Brand: Color = Color(0xff07c160),
    val Action: Color = Color(0xff087b40), val OnAction: Color = Color.White,
    val ActionContainer: Color = Color(0xffe8f5ec),
    val Background: Color = Color(0xfff7f7f7), val Header: Color = Color(0xffededed),
    val Surface: Color = Color.White, val Text: Color = Color(0xff191919),
    val Secondary: Color = Color(0xff666666), val Divider: Color = Color(0xffe5e5e5),
    val Fill: Color = Color(0xfff2f2f2), val Disabled: Color = Color(0xffa3a3a3),
    val Warning: Color = Color(0xffc52b29), val OnWarning: Color = Color.White,
    val Outline: Color = Color(0xff808080),
)

val LightRemotePalette = RemotePalette()
val DarkRemotePalette = RemotePalette(
    Action = Color(0xff55d990), OnAction = Color(0xff092817), ActionContainer = Color(0xff153d29),
    Background = Color(0xff111111), Header = Color(0xff111111), Surface = Color(0xff1e1e1e),
    Text = Color(0xffe6e6e6), Secondary = Color(0xffadadad), Divider = Color(0xff383838),
    Fill = Color(0xff292929), Disabled = Color(0xff737373), Warning = Color(0xffff8585),
    OnWarning = Color(0xff390b0b), Outline = Color(0xff858585),
)
private val LocalRemotePalette = staticCompositionLocalOf { LightRemotePalette }

object RemoteColors {
    val Brand: Color @Composable get() = LocalRemotePalette.current.Brand
    val Action: Color @Composable get() = LocalRemotePalette.current.Action
    val ActionContainer: Color @Composable get() = LocalRemotePalette.current.ActionContainer
    val OnAction: Color @Composable get() = LocalRemotePalette.current.OnAction
    val Background: Color @Composable get() = LocalRemotePalette.current.Background
    val Header: Color @Composable get() = LocalRemotePalette.current.Header
    val Surface: Color @Composable get() = LocalRemotePalette.current.Surface
    val Text: Color @Composable get() = LocalRemotePalette.current.Text
    val Secondary: Color @Composable get() = LocalRemotePalette.current.Secondary
    val Divider: Color @Composable get() = LocalRemotePalette.current.Divider
    val Fill: Color @Composable get() = LocalRemotePalette.current.Fill
    val Disabled: Color @Composable get() = LocalRemotePalette.current.Disabled
    val Warning: Color @Composable get() = LocalRemotePalette.current.Warning
}

/** Dimensions from AIVoice phoneRemoteApp, with scrolling for short windows / large text. */
object RemoteDimensions {
    val MirrorPreviewHeight = 300.dp
    val MirrorRemoteSourceHeight = 760.dp
    const val MirrorRemotePortraitFraction = 1f / 3f
    const val MirrorRemoteLandscapeFraction = 0.5f
    val SettingRowHeight = 56.dp
    val ManagementWidth = 560.dp
    val ManagementDialogHeight = 640.dp
    val FileGridThumbnail = 80.dp
    val FilePreviewHeight = 280.dp
    val FileGridHeight = 144.dp
    val ManagementRowHeight = 72.dp
    val ManagementFileIcon = 40.dp
    val ApplicationIcon = 40.dp
    val DeviceIcon = 24.dp
    val DeviceSourceIcon = 14.dp
    val GamepadPagePadding = 8.dp
    val GamepadPanelPadding = 16.dp
    val GamepadDpadSize = 216.dp
    val GamepadDirectionSize = 72.dp
    val GamepadNesButtonSize = 88.dp
    val GamepadCompactNesButtonSize = 72.dp
    val GamepadSessionButtonHeight = 64.dp
    val GamepadGap = 8.dp
    val GamepadMinStickSize = 64.dp
    val GamepadMinFaceSize = 192.dp
    val GamepadButtonLabelPadding = 24.dp
    const val GamepadFaceWidthFraction = 0.56f
    val GamepadContentWidth = 960.dp
    val GamepadDualStickMinWidth = 560.dp
    val GamepadFaceSize = 240.dp
    val GamepadButtonSize = 80.dp
    const val GamepadFaceButtonFraction = 1f / 3f
    val TouchpadMaximumHeight = 240.dp
    val TouchpadMinimumHeight = 48.dp
    val TouchpadViewportPadding = 64.dp
    val Width = 390.dp
    val OuterPadding = 14.dp
    val InnerPadding = 24.dp
    val VerticalPadding = 22.dp
    val Dpad = 220.dp
    val Confirm = 82.dp
    val GamepadRowMinWidth = 400.dp
    val GamepadStickSize = 132.dp
    val GamepadStickKnobRadius = 22.dp
    val MinimumControlsHeight = 600.dp
}

@Composable
fun RemoteTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkRemotePalette else LightRemotePalette
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalRemotePalette provides colors) {
        MaterialTheme(
            colorScheme = base.copy(
                primary = colors.Action, onPrimary = colors.OnAction,
                primaryContainer = colors.ActionContainer, onPrimaryContainer = colors.Action,
                secondary = colors.Action, onSecondary = colors.OnAction,
                secondaryContainer = colors.Fill, onSecondaryContainer = colors.Text,
                tertiary = colors.Action, onTertiary = colors.OnAction,
                tertiaryContainer = colors.ActionContainer, onTertiaryContainer = colors.Text,
                background = colors.Background, onBackground = colors.Text,
                surface = colors.Surface, onSurface = colors.Text,
                surfaceVariant = colors.Fill, onSurfaceVariant = colors.Secondary,
                surfaceTint = colors.Surface,
                surfaceDim = colors.Background, surfaceBright = colors.Surface,
                surfaceContainerLowest = colors.Background, surfaceContainerLow = colors.Surface,
                surfaceContainer = colors.Surface, surfaceContainerHigh = colors.Fill,
                surfaceContainerHighest = colors.Fill,
                inverseSurface = if (darkTheme) LightRemotePalette.Surface else DarkRemotePalette.Surface,
                inverseOnSurface = if (darkTheme) LightRemotePalette.Text else DarkRemotePalette.Text,
                inversePrimary = if (darkTheme) LightRemotePalette.Action else DarkRemotePalette.Action,
                outline = colors.Outline, outlineVariant = colors.Divider,
                error = colors.Warning, onError = colors.OnWarning,
                errorContainer = colors.Fill, onErrorContainer = colors.Warning,
            ),
            typography = Typography(
                titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
                titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
                titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
                bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
                labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
                labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            ),
            shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(8.dp)),
            content = { CompositionLocalProvider(LocalContentColor provides colors.Text, content = content) },
        )
    }
}

@Composable
fun PageContent(modifier: Modifier = Modifier, spacing: Dp = 16.dp, contentPadding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacing), content = content,
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = RemoteColors.Surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun SectionTitle(title: String, description: String? = null) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
}

@Composable
fun ActionButton(label: String, enabled: Boolean = true, action: () -> Unit) {
    Button(action, enabled = enabled, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
}

@Composable
fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
fun ResultPanel(output: String) {
    Panel {
        SectionTitle("执行结果")
        if (output.isBlank() || output == "暂无结果") {
            Text("还没有执行结果", style = MaterialTheme.typography.bodyLarge)
            Text("发送文字指令或操作设备后，可在这里查看完整反馈。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        } else SelectionContainer { Text(output, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Technical output stays available without dominating a tool's first screen. */
@Composable
fun OperationDetails(output: String) {
    if (output.isBlank()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Panel {
        TextButton({ expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("operation-details")) {
            Text(if (expanded) "收起操作详情" else "查看操作详情")
        }
        if (expanded) SelectionContainer { Text(output, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun AppearanceSettings(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit) = AppearanceSettings(mode, onModeChange) {}

@Composable
fun AppearanceSettings(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit, additionalSettings: @Composable () -> Unit) {
    PageContent(Modifier.testTag("settings-page")) {
        Text("外观", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text("选择适合你的显示方式", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = RemoteColors.Divider, thickness = 0.5.dp)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .testTag("theme-${item.name.lowercase()}")
                            .selectable(selected = mode == item, role = Role.RadioButton, onClick = { onModeChange(item) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        RadioButton(selected = mode == item, onClick = null)
                    }
                }
            }
        }
        Text("跟随系统会随设备的深色模式自动切换。设置仅保存在当前控制端。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        additionalSettings()
    }
}

@Composable
fun StopServiceConfirmation(enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("停止目标服务？") },
        text = { Text("停止后将断开当前遥控连接。你可以稍后在服务管理中重新启动。") },
        confirmButton = { TextButton(onConfirm, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = RemoteColors.Warning)) { Text("停止服务") } },
        dismissButton = { TextButton(onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } },
    )
}

/** One semantic control and one touch target for the label, explanation and indicator. */
@Composable
fun ToggleSettingRow(label: String, checked: Boolean, enabled: Boolean = true,
    description: String? = null, checkbox: Boolean = false, modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = RemoteDimensions.SettingRowHeight)
        .toggleable(value = checked, enabled = enabled,
            role = if (checkbox) Role.Checkbox else Role.Switch, onValueChange = onCheckedChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary) }
        }
        if (checkbox) Checkbox(checked, onCheckedChange = null, enabled = enabled)
        else Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun TextSubmissionHint(connected: Boolean, configured: Boolean, submitting: Boolean) {
    val hint = when {
        !connected -> "请先点击右上角连接设备；输入草稿会保留。"
        submitting -> "指令正在执行，请等待当前请求结束。"
        !configured -> "请在模型配置中保存配置，并更新到目标设备后发送。"
        else -> "发送后可在执行结果中查看完整反馈。"
    }
    Text(hint, style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
}
