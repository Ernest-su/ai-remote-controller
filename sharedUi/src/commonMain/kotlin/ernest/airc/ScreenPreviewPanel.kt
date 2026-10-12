package ernest.airc

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreenPreviewPanel(state: ScreenPreviewState, image: ImageBitmap?, available: Boolean,
    onStart: (Int) -> Unit, onStop: () -> Unit) {
    var interval by rememberSaveable { mutableStateOf(1000) }
    Panel {
        SectionTitle("低帧率屏幕预览（原型）", "连续读取目标画面，无音频；实际速度受捕获与网络影响。")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1000,2000,5000).forEach { value ->
                FilterChip(interval == value, { interval = value }, enabled = !state.running && !state.stopping,
                    modifier = Modifier.heightIn(min = 48.dp), label = { Text("间隔 ${value / 1000} 秒") })
            }
        }
        ActionButton("开始屏幕预览", available && !state.running && !state.stopping, { onStart(interval) })
        if(state.stopping) Text("正在关闭预览连接…", color = RemoteColors.Secondary)
        if(state.running) {
            ActionButton("停止屏幕预览", true, onStop)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.frame?.let { frame ->
            InfoRow("已接收画面", "${frame.sequence} 帧 · ${frame.width} × ${frame.height}")
            if(image != null) Image(image, "目标连续画面预览", Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp).testTag("screen-preview-image"), contentScale = ContentScale.Fit)
        }
        Text("每次最长一分钟；离开页面、旋转或断线会停止，重新连接后手动开始。保护内容可能黑屏。", color = RemoteColors.Secondary)
        state.message?.let { Text(it, color = RemoteColors.Secondary) }
        state.error?.let { Text(it, color = RemoteColors.Warning) }
    }
}
