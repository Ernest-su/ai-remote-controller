package ernest.airc

import android.view.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ernest.ascrcpy.scrcpy.ScrcpyState

@Composable internal fun AndroidScreenMirror(state:RemoteUiState,model:RemoteViewModel) {
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
    Text("动态预览",style=MaterialTheme.typography.titleMedium)
    Text("1920 · 8 Mbps · 60 fps",color=RemoteColors.Secondary)
    val size=(mirror as? ScrcpyState.Streaming)?.videoSize
    MirrorSurface(surfaceEpoch,active,size?.let{it.width.toFloat()/it.height} ?: (16f/9f),
        onSurface={surface=it},onDestroyed=model.ascrcpyPreview::stop)
    ActionButton("开始屏幕镜像",available&&!active&&surface?.isValid==true){surface?.takeIf{it.isValid}?.let{model.ascrcpyPreview.start(it)}}
    if(active)ActionButton("停止屏幕镜像",true,model.ascrcpyPreview::stop)
    when(val value=mirror){
        ScrcpyState.Idle->Text("通过设备页记录的 ADB 连接开始动态预览。",color=RemoteColors.Secondary)
        is ScrcpyState.Failed->Text(value.cause.message ?: "动态预览失败",color=MaterialTheme.colorScheme.error)
        is ScrcpyState.Streaming->InfoRow("预览尺寸","${value.videoSize.width} × ${value.videoSize.height}")
        else->LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
