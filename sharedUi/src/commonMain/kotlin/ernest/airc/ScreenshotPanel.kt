package ernest.airc

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun ScreenshotPanel(image: ImageBitmap?, loading: Boolean, available: Boolean, error: String?,
    onCapture: () -> Unit, onSave: () -> Unit, onShare: (() -> Unit)? = null, preview: (@Composable () -> Unit)? = null, unavailableMessage: String = "请连接支持截图工具的服务。") = PageContent(Modifier.testTag("screenshot-page")) {
    preview?.invoke()
    Panel {
        SectionTitle("单张截图", "获取目标当前画面，可刷新、保存。")
        ActionButton(if (loading) "正在获取截图…" else "获取 / 刷新截图", available && !loading, onCapture)
        if (!available) Text(unavailableMessage, color = RemoteColors.Secondary)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = RemoteColors.Secondary) }
        if (image != null) {
            Image(image, "目标设备截图预览", Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp).testTag("screenshot-preview"), contentScale = ContentScale.Fit)
            ActionButton("保存 PNG", !loading, onSave)
            onShare?.let { share -> OutlinedButton(share, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("分享截图") } }
        } else if (!loading) Text("尚未获取截图。", color = RemoteColors.Secondary)
    }
}
