package ernest.airc

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView

/** Replace the buffer-bearing view after a session ends, so its last frame cannot linger. */
@Composable
internal fun MirrorSurface(epoch:Long,active:Boolean,ratio:Float,onSurface:(Surface?)->Unit,onDestroyed:()->Unit) {
    var current by remember{mutableStateOf<Surface?>(null)}
    val surfaceCallback by rememberUpdatedState(onSurface)
    val destroyedCallback by rememberUpdatedState(onDestroyed)
    val activeNow by rememberUpdatedState(active)
    val latestEpoch by rememberUpdatedState(epoch)
    DisposableEffect(epoch){surfaceCallback(null);onDispose{}}
    key(epoch){
        AndroidView(factory={context->SurfaceView(context).apply{
            holder.addCallback(object:SurfaceHolder.Callback{
                override fun surfaceCreated(holder:SurfaceHolder){if(epoch==latestEpoch){current=holder.surface;surfaceCallback(current)}}
                override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){if(epoch==latestEpoch){current=holder.surface;surfaceCallback(current)}}
                override fun surfaceDestroyed(holder:SurfaceHolder){
                    // A late callback from the retired view must not cancel a new session.
                    if(current===holder.surface){current=null;surfaceCallback(null);if(activeNow&&epoch==latestEpoch)destroyedCallback()}
                }
            })
        }},modifier=Modifier.fillMaxWidth().aspectRatio(ratio).testTag("screen-mirror-image"))
    }
}
