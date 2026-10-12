package ernest.airc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun TouchpadPanel(connected: Boolean, tools: List<String>, busy: Boolean, output: String, sendGesture: (String, kotlinx.serialization.json.JsonObject) -> Boolean) {
    val supported = listOf("remote.tap", "remote.swipe", "remote.long_press").all { it in tools }
    val enabled = connected && supported && !busy
    val scope = rememberCoroutineScope()
    var tapJob by remember { mutableStateOf<Job?>(null) }
    var previousTap by remember { mutableStateOf(Offset.Zero) }
    fun tap(point: Offset, count: Int = 1) = sendGesture("remote.tap", buildJsonObject {
        put("x", point.x); put("y", point.y); if (count == 2) put("count", count)
    })
    val send by rememberUpdatedState<(PadGesture) -> Unit>({ gesture ->
        if (enabled) {
            when {
                gesture.dragged -> {
                    tapJob?.cancel()
                    sendGesture("remote.swipe", buildJsonObject {
                        put("startX", gesture.start.x); put("startY", gesture.start.y)
                        put("endX", gesture.end.x); put("endY", gesture.end.y)
                        put("durationMs", gesture.durationMs.coerceIn(100, 5000))
                    })
                }
                gesture.durationMs >= 500 -> {
                    tapJob?.cancel()
                    sendGesture("remote.long_press", buildJsonObject {
                        put("x", gesture.end.x); put("y", gesture.end.y)
                        put("durationMs", gesture.durationMs.coerceIn(500, 5000))
                    })
                }
                tapJob?.isActive == true && (gesture.end - previousTap).getDistance() < 0.1f -> {
                    tapJob?.cancel(); tap(gesture.end, 2)
                }
                else -> {
                    // Separate clicks at different positions submit the first before starting another.
                    if (tapJob?.isActive == true) { tapJob?.cancel(); tap(previousTap) }
                    previousTap = gesture.end
                    tapJob = scope.launch { delay(300); tap(gesture.end) }
                }
            }
        }
    })
    DisposableEffect(enabled) { onDispose { tapJob?.cancel() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val touchHeight=(maxHeight-RemoteDimensions.TouchpadViewportPadding).coerceIn(RemoteDimensions.TouchpadMinimumHeight,RemoteDimensions.TouchpadMaximumHeight)
        PageContent(Modifier.testTag("touchpad-page")) {
        Panel {
            SectionTitle("触控板", "触控区映射目标屏幕。点击、双击、滑动或按住后松开；拖动只在松开时发送。")
            Text(when { !connected -> "请先连接设备"; !supported -> "目标服务不支持触控工具，请更新服务";
                busy -> "正在发送手势…"; output.isNotBlank() -> "操作已返回，可以继续"; else -> "可以操作" }, color = RemoteColors.Secondary)
            Box(Modifier.fillMaxWidth().height(touchHeight).testTag("touchpad-area")
                .background(RemoteColors.Fill, MaterialTheme.shapes.small)
                .border(1.dp, RemoteColors.Divider, MaterialTheme.shapes.small)
                .semantics { contentDescription = "目标屏幕触控区"; if (!enabled) disabled() }
                .pointerInput(enabled) { if (enabled) readPadGestures { send(it) } },
                contentAlignment = Alignment.Center) {
                Text("点击 / 双击 / 滑动 / 长按", color = RemoteColors.Secondary)
            }
            ActionButton("点击屏幕中心", enabled) { tapJob?.cancel(); tap(Offset(0.5f, 0.5f)) }
            OutlinedButton({ tapJob?.cancel(); sendGesture("remote.long_press", buildJsonObject {
                put("x", 0.5); put("y", 0.5)
            }) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("长按屏幕中心") }
        }
        OperationDetails(output)
    }
    }
}
