package ernest.airc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Shared code-native vectors keep Android and JVM management surfaces consistent. */
internal object ManagementIcons {
    private fun vector(name: String, draw: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()
    val Upload = vector("Upload") { moveTo(12f,16f);lineTo(12f,3f);moveTo(7f,8f);lineTo(12f,3f);lineTo(17f,8f);moveTo(3f,15f);lineTo(3f,21f);lineTo(21f,21f);lineTo(21f,15f) }
    val Storage = vector("Storage") { moveTo(4f,20f);lineTo(4f,12f);lineTo(8f,12f);lineTo(8f,20f);close();moveTo(10f,20f);lineTo(10f,4f);lineTo(14f,4f);lineTo(14f,20f);close();moveTo(16f,20f);lineTo(16f,8f);lineTo(20f,8f);lineTo(20f,20f);close() }
    val Image = vector("Image") { moveTo(3f,4f);lineTo(21f,4f);lineTo(21f,20f);lineTo(3f,20f);close();moveTo(3f,17f);lineTo(9f,11f);lineTo(14f,16f);lineTo(17f,13f);lineTo(21f,17f);moveTo(16f,8f);lineTo(16.1f,8f) }
    val List = vector("List") { moveTo(4f,6f);lineTo(6f,6f);moveTo(10f,6f);lineTo(20f,6f);moveTo(4f,12f);lineTo(6f,12f);moveTo(10f,12f);lineTo(20f,12f);moveTo(4f,18f);lineTo(6f,18f);moveTo(10f,18f);lineTo(20f,18f) }
    val Search = vector("Search") { moveTo(17f,17f); lineTo(21f,21f); moveTo(11f,4f); curveTo(20f,4f,20f,18f,11f,18f); curveTo(2f,18f,2f,4f,11f,4f); close() }
    val Folder = vector("Folder") { moveTo(3f,6f); lineTo(10f,6f); lineTo(12f,9f); lineTo(21f,9f); lineTo(21f,20f); lineTo(3f,20f); close(); moveTo(3f,9f); lineTo(12f,9f) }
    val File = vector("File") { moveTo(5f,3f); lineTo(14f,3f); lineTo(19f,8f); lineTo(19f,21f); lineTo(5f,21f); close(); moveTo(14f,3f); lineTo(14f,8f); lineTo(19f,8f); moveTo(8f,13f); lineTo(16f,13f); moveTo(8f,17f); lineTo(14f,17f) }
    val Apps = vector("Apps") { moveTo(4f,4f); lineTo(10f,4f); lineTo(10f,10f); lineTo(4f,10f); close(); moveTo(14f,4f); lineTo(20f,4f); lineTo(20f,10f); lineTo(14f,10f); close(); moveTo(4f,14f); lineTo(10f,14f); lineTo(10f,20f); lineTo(4f,20f); close(); moveTo(14f,14f); lineTo(20f,14f); lineTo(20f,20f); lineTo(14f,20f); close() }
    val More = vector("More") { moveTo(5f,12f); lineTo(5.1f,12f); moveTo(12f,12f); lineTo(12.1f,12f); moveTo(19f,12f); lineTo(19.1f,12f) }
    val Trash = vector("Trash") { moveTo(4f,6f);lineTo(20f,6f);moveTo(9f,6f);lineTo(9f,3f);lineTo(15f,3f);lineTo(15f,6f);moveTo(6f,6f);lineTo(7f,21f);lineTo(17f,21f);lineTo(18f,6f);moveTo(10f,10f);lineTo(10f,17f);moveTo(14f,10f);lineTo(14f,17f) }
    val Close = vector("Close") { moveTo(6f,6f); lineTo(18f,18f); moveTo(18f,6f); lineTo(6f,18f) }
    val Refresh = vector("Refresh") { moveTo(20f,4f); lineTo(20f,10f); lineTo(14f,10f); moveTo(20f,10f); curveTo(18f,1f,4f,3f,4f,12f); curveTo(4f,21f,17f,23f,20f,15f) }
    val Up = vector("Up") { moveTo(5f,11f); lineTo(12f,4f); lineTo(19f,11f); moveTo(12f,4f); lineTo(12f,21f) }
    val Next = vector("Next") { moveTo(9f,5f); lineTo(16f,12f); lineTo(9f,19f) }
    val Stop = vector("Stop") { moveTo(6f,6f); lineTo(18f,6f); lineTo(18f,18f); lineTo(6f,18f); close() }
    val Play = vector("Play") { moveTo(8f,4f); lineTo(20f,12f); lineTo(8f,20f); close() }
    val AddFolder = vector("AddFolder") { moveTo(3f,6f); lineTo(10f,6f); lineTo(12f,9f); lineTo(21f,9f); lineTo(21f,20f); lineTo(3f,20f); close(); moveTo(12f,11f); lineTo(12f,17f); moveTo(9f,14f); lineTo(15f,14f) }
    val Transfer = vector("Transfer") { moveTo(7f,20f); lineTo(7f,4f); moveTo(3f,8f); lineTo(7f,4f); lineTo(11f,8f); moveTo(17f,4f); lineTo(17f,20f); moveTo(13f,16f); lineTo(17f,20f); lineTo(21f,16f) }
}

@Composable
internal fun ManagementIconButton(icon: ImageVector, label: String, enabled: Boolean = true, tag: String = label, action: () -> Unit) {
    IconButton(action, enabled = enabled, modifier = Modifier.size(48.dp).testTag(tag)) {
        Icon(icon, label, Modifier.size(24.dp), tint = if (enabled) RemoteColors.Action else RemoteColors.Disabled)
    }
}

@Composable
fun ManagementDialog(title: String, tag: String, onDismiss: () -> Unit, grouped: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = RemoteDimensions.ManagementWidth).fillMaxWidth().padding(16.dp)
            .heightIn(max = RemoteDimensions.ManagementDialogHeight).testTag(tag), shape = MaterialTheme.shapes.small, color = RemoteColors.Surface) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    ManagementIconButton(ManagementIcons.Close, "关闭$title", tag = "$tag-close", action = onDismiss)
                }
                HorizontalDivider(color = RemoteColors.Divider, thickness = 0.5.dp)
                Column(Modifier.weight(1f, fill = false).background(if (grouped) RemoteColors.Background else RemoteColors.Surface).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}

@Composable
internal fun ManagementEmpty(icon: ImageVector, title: String, message: String, actionLabel: String? = null, enabled: Boolean = true, action: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(40.dp), tint = RemoteColors.Secondary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
        if (actionLabel != null) OutlinedButton(action, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text(actionLabel) }
    }
}

@Composable
internal fun ManagementMessage(text: String, error: Boolean = false, actionLabel: String? = null, action: () -> Unit = {}) {
    Surface(color = RemoteColors.Surface, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) RemoteColors.Warning else RemoteColors.Secondary)
            if (actionLabel != null) TextButton(action, Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
        }
    }
}
