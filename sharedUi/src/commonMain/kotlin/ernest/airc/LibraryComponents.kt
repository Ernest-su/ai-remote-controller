package ernest.airc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal object LibraryIcons {
    private fun vector(name: String, fill: Boolean = false, draw: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = if (fill) SolidColor(Color.Black) else null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()
    private val star: PathBuilder.() -> Unit = { moveTo(12f,3f);lineTo(14.8f,8.8f);lineTo(21.2f,9.7f);lineTo(16.6f,14.2f);lineTo(17.7f,20.6f);lineTo(12f,17.6f);lineTo(6.3f,20.6f);lineTo(7.4f,14.2f);lineTo(2.8f,9.7f);lineTo(9.2f,8.8f);close() }
    val Star = vector("Favorite", draw = star)
    val StarFilled = vector("FavoriteSelected", true, star)
    val Back = vector("Back") { moveTo(14f,5f);lineTo(7f,12f);lineTo(14f,19f) }
    val Down = vector("Down") { moveTo(5f,13f);lineTo(12f,20f);lineTo(19f,13f);moveTo(12f,20f);lineTo(12f,3f) }
    val Check = vector("Check") { moveTo(5f,12f);lineTo(10f,17f);lineTo(20f,7f) }
}

@Composable
fun LibraryDialog(title: String, tag: String, onBack: () -> Unit, subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}, footer: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(RemoteColors.Background).safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Surface(Modifier.widthIn(max = RemoteDimensions.ManagementWidth).fillMaxWidth().fillMaxHeight().testTag(tag), color = RemoteColors.Background) {
                Column {
                    Surface(color = RemoteColors.Header) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            ManagementIconButton(LibraryIcons.Back, "返回$title", tag = "$tag-close", action = onBack)
                            Column(Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 8.dp)) {
                                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            actions()
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), content = content)
                    footer?.let { bottom ->
                        HorizontalDivider(color = RemoteColors.Divider, thickness = 0.5.dp)
                        Surface(color = RemoteColors.Surface) { Box(Modifier.fillMaxWidth().padding(16.dp)) { bottom() } }
                    }
                }
            }
        }
    }
}

@Composable
fun ApplicationListRow(app: InstalledApp, onClick: () -> Unit, icon: @Composable () -> Unit = {},
    enabled: Boolean = true, clickLabel: String = "打开应用", trailing: @Composable RowScope.() -> Unit = {}) {
    Surface(color = RemoteColors.Surface) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = RemoteDimensions.ManagementRowHeight).testTag("app-row-${app.packageName}"), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(enabled = enabled, role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(RemoteDimensions.ApplicationIcon).background(RemoteColors.Fill, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                        Icon(ManagementIcons.Apps, null, Modifier.size(24.dp), tint = RemoteColors.Action); icon()
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(app.label ?: app.packageName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (app.label != null) Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when (app.system) { true -> "系统应用"; false -> "用户安装"; null -> "类型未知" }, style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
                    }
                }
                trailing()
            }
            HorizontalDivider(Modifier.padding(start = 68.dp), color = RemoteColors.Divider, thickness = 0.5.dp)
        }
    }
}

@Composable
fun AppFavoriteButton(app: InstalledApp, favorite: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    IconToggleButton(favorite, { onToggle() }, enabled = enabled,
        modifier = Modifier.size(48.dp).testTag("app-favorite-${app.packageName}").semantics { stateDescription = if (favorite) "已收藏" else "未收藏" }) {
        Icon(if (favorite) LibraryIcons.StarFilled else LibraryIcons.Star,
            (if (favorite) "取消收藏 " else "收藏 ") + (app.label ?: app.packageName), Modifier.size(24.dp),
            tint = if (!enabled) RemoteColors.Disabled else if (favorite) RemoteColors.Action else RemoteColors.Secondary)
    }
}
