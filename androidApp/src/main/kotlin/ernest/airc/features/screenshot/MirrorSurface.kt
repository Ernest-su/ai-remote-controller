package ernest.airc

import android.view.Surface
import android.view.MotionEvent
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
internal fun MirrorSurface(epoch:Long,active:Boolean,ratio:Float,onSurface:(Surface?)->Unit,onDestroyed:()->Unit,modifier:Modifier=Modifier,
    onTouch:((Int,Long,Float,Float,Float)->Unit)?=null) {
    var current by remember{mutableStateOf<Surface?>(null)}
    val surfaceCallback by rememberUpdatedState(onSurface)
    val destroyedCallback by rememberUpdatedState(onDestroyed)
    val activeNow by rememberUpdatedState(active)
    val pointers=remember { mutableMapOf<Long,Pair<Float,Float>>() }
    DisposableEffect(active,onTouch!=null) {
        val cancel=onTouch
        onDispose {
            pointers.forEach { (id,point) -> cancel?.invoke(MotionEvent.ACTION_CANCEL,id,point.first,point.second,0f) }
            pointers.clear()
        }
    }
    val touchCallback by rememberUpdatedState(onTouch)
    val latestEpoch by rememberUpdatedState(epoch)
    DisposableEffect(epoch){surfaceCallback(null);onDispose{}}
    key(epoch){
        AndroidView(factory={context->SurfaceView(context).apply{
            setOnTouchListener { view,event ->
                val callback=touchCallback
                if(!activeNow || callback==null) false else {
                    fun send(index:Int,action:Int) {
                        val id=event.getPointerId(index).toLong()
                        val x=(event.getX(index)/view.width).coerceIn(0f,1f)
                        val y=(event.getY(index)/view.height).coerceIn(0f,1f)
                        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) pointers.remove(id) else pointers[id]=x to y
                        callback(action,id,x,y,if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) 0f else event.getPressure(index))
                    }
                    when(event.actionMasked) {
                        MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> send(event.actionIndex,MotionEvent.ACTION_DOWN)
                        MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> send(event.actionIndex,MotionEvent.ACTION_UP)
                        MotionEvent.ACTION_MOVE -> repeat(event.pointerCount){send(it,MotionEvent.ACTION_MOVE)}
                        MotionEvent.ACTION_CANCEL -> repeat(event.pointerCount){send(it,MotionEvent.ACTION_CANCEL)}
                    }
                    view.parent?.requestDisallowInterceptTouchEvent(event.actionMasked!=MotionEvent.ACTION_UP && event.actionMasked!=MotionEvent.ACTION_CANCEL)
                    true
                }
            }
            holder.addCallback(object:SurfaceHolder.Callback{
                override fun surfaceCreated(holder:SurfaceHolder){if(epoch==latestEpoch){current=holder.surface;surfaceCallback(current)}}
                override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){if(epoch==latestEpoch){current=holder.surface;surfaceCallback(current)}}
                override fun surfaceDestroyed(holder:SurfaceHolder){
                    // A late callback from the retired view must not cancel a new session.
                    if(current===holder.surface){current=null;surfaceCallback(null);if(activeNow&&epoch==latestEpoch)destroyedCallback()}
                }
            })
        }},modifier=modifier.fillMaxWidth().aspectRatio(ratio).testTag("screen-mirror-image"))
    }
}
