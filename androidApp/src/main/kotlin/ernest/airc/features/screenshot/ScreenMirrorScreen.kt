package ernest.airc

import android.view.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ernest.ascrcpy.scrcpy.ScrcpyState

@Composable internal fun AndroidScreenMirror(state:RemoteUiState,model:RemoteViewModel,fullscreen:Boolean=false,onFullscreen:(Boolean)->Unit={}) {
    val mirror by model.ascrcpyPreview.state.collectAsState()
    val surfaceEpoch by model.ascrcpyPreview.surfaceEpoch.collectAsState()
    val shot by model.screenshots.state.collectAsState()
    var surface by remember{mutableStateOf<Surface?>(null)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle){mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(lifecycle,model){
        val observer=LifecycleEventObserver{_,event->
            foreground=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_STOP)model.ascrcpyPreview.stop()
        }
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer);model.ascrcpyPreview.stop()}
    }
    val active=mirror !is ScrcpyState.Idle && mirror !is ScrcpyState.Failed
    val available=foreground&&state.connected&&!state.busy&&!shot.loading
    val size=(mirror as? ScrcpyState.Streaming)?.videoSize
    val ratio=size?.let{it.width.toFloat()/it.height} ?: (16f/9f)
    var showResults by remember { mutableStateOf(false) }
    if(showResults) AlertDialog(onDismissRequest={showResults=false},title={Text("执行结果")},
        text={ androidx.compose.foundation.text.selection.SelectionContainer { Text(state.output,Modifier.heightIn(max=RemoteDimensions.ManagementDialogHeight).then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))) } },
        confirmButton={TextButton({showResults=false}) {Text("关闭")}})
    var expanded by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val density=LocalDensity.current
    Column(if(fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        if(!fullscreen) {
            Text("动态预览",style=MaterialTheme.typography.titleMedium)
            Text("1920 · 8 Mbps · 60 fps",color=RemoteColors.Secondary)
        }
        BoxWithConstraints(if(fullscreen) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(RemoteDimensions.MirrorPreviewHeight), contentAlignment=Alignment.Center) {
            // Bound BOTH axes before applying aspectRatio: neither portrait nor landscape stretches.
            val viewportHeight=maxHeight
            val videoWidth=minOf(maxWidth,maxHeight*ratio)
            MirrorSurface(surfaceEpoch,active,ratio,onSurface={surface=it},onDestroyed=model.ascrcpyPreview::stop,
                modifier=Modifier.width(videoWidth),
                onTouch=if(available && mirror is ScrcpyState.Streaming) model.ascrcpyPreview::touch else null)
            if(fullscreen) {
                val panelWidth=minOf(maxWidth,RemoteDimensions.MirrorRemoteWidth)
                val panelHeight=if(expanded) maxHeight else 48.dp
                val limitX=with(density){(maxWidth-panelWidth).toPx().coerceAtLeast(0f)}
                val limitY=with(density){(maxHeight-panelHeight).toPx().coerceAtLeast(0f)}
                Surface(Modifier.align(Alignment.TopStart).offset { IntOffset(offsetX.coerceIn(0f,limitX).roundToInt(),offsetY.coerceIn(0f,limitY).roundToInt()) }
                    .width(panelWidth).heightIn(max=maxHeight).testTag("mirror-floating-remote"),
                    color=RemoteColors.Surface,shape=MaterialTheme.shapes.small) {
                    Column {
                        Row(Modifier.fillMaxWidth().pointerInput(limitX,limitY) {
                            detectDragGestures { change,drag -> change.consume();offsetX=(offsetX+drag.x).coerceIn(0f,limitX);offsetY=(offsetY+drag.y).coerceIn(0f,limitY) }
                        },verticalAlignment=Alignment.CenterVertically) {
                            TextButton({onFullscreen(false)},Modifier.heightIn(min=48.dp)) { Text("退出全屏") }
                            TextButton({expanded=!expanded},Modifier.heightIn(min=48.dp).testTag("mirror-toggle-remote")) { Text(if(expanded) "收起遥控" else "悬浮遥控") }
                        }
                        if(expanded) Box(Modifier.fillMaxWidth().heightIn(max=viewportHeight-48.dp)) {
                            RemoteControls(state,model) { showResults=true }
                        }
                    }
                }
            }
        }
        if(!fullscreen) {
            ActionButton("开始屏幕镜像",available&&!active&&surface?.isValid==true){surface?.takeIf{it.isValid}?.let{model.ascrcpyPreview.start(it)}}
            if(active)ActionButton("停止屏幕镜像",true,model.ascrcpyPreview::stop)
            ActionButton("全屏预览",active) { onFullscreen(true) }
            when(val value=mirror){
                ScrcpyState.Idle->Text("通过设备页记录的 ADB 连接开始动态预览。",color=RemoteColors.Secondary)
                is ScrcpyState.Failed->Text(value.cause.message ?: "动态预览失败",color=RemoteColors.Warning)
                is ScrcpyState.Streaming->InfoRow("预览尺寸","${value.videoSize.width} × ${value.videoSize.height}")
                else->LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } else if(!active) {
            Text(if(mirror is ScrcpyState.Failed) "动态预览失败，请退出全屏后重试。" else "预览已停止，请退出全屏后重新开始。",color=RemoteColors.Secondary)
        }
    }
}
