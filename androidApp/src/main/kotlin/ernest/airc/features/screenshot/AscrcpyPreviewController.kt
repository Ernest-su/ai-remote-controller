package ernest.airc

import android.content.Context
import android.view.Surface
import ernest.ascrcpy.scrcpy.ScrcpyClient
import ernest.ascrcpy.scrcpy.ScrcpyConfig
import ernest.ascrcpy.scrcpy.ScrcpyState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Owns a dedicated ADB session; preview cleanup never closes the runtime's ADB connection. */
class AscrcpyPreviewController(
    private val context:Context,
    private val scope:CoroutineScope,
    private val connect:suspend ()->AdbSession,
) {
    private val mutable=MutableStateFlow<ScrcpyState>(ScrcpyState.Idle)
    val state:StateFlow<ScrcpyState> = mutable.asStateFlow()
    private val retired=MutableStateFlow(0L)
    val surfaceEpoch:StateFlow<Long> = retired.asStateFlow()
    @Volatile private var activeClient:ScrcpyClient?=null
    private val inputMutex=kotlinx.coroutines.sync.Mutex()
    private var job:Job?=null
    private var generation=0L
    @Synchronized fun start(surface:Surface) {
        stop()
        val token=++generation
        mutable.value=ScrcpyState.InstallingServer
        job=scope.launch(Dispatchers.IO){
            var session:AdbSession?=null;var client:ScrcpyClient?=null
            try {
                session=connect();ensureActive()
                client=ScrcpyClient(session.client)
                withTimeout(45_000){
                    context.assets.open("scrcpy-server-v4.0").use{client.start(it,surface,ScrcpyConfig())}
                    client.state.first{
                        publish(token,it)
                        it is ScrcpyState.Failed || (it is ScrcpyState.Streaming && it.renderedFrames>0)
                    }
                }
                synchronized(this@AscrcpyPreviewController){if(token==generation)activeClient=client}
                client.state.collect{
                    publish(token,it)
                    if(it is ScrcpyState.Failed)throw it.cause
                }
            } catch(error:TimeoutCancellationException){publish(token,ScrcpyState.Failed(IllegalStateException("动态预览启动超时",error)))}
            catch(error:CancellationException){throw error}
            catch(error:Exception){publish(token,ScrcpyState.Failed(error))}
            finally {
                client?.close()
                withContext(NonCancellable+Dispatchers.IO){
                    try{withTimeout(5000){client?.removeServer()}}catch(_:Exception){}
                    session?.close()
                }
                synchronized(this@AscrcpyPreviewController){if(token==generation)job=null}
            }
        }
    }
    /** One ordered stream preserves pointer down/move/up ordering and packet boundaries. */
    fun touch(action:Int,pointerId:Long,x:Float,y:Float,pressure:Float) {
        val client=activeClient ?: return
        val token=generation
        scope.launch(start=CoroutineStart.UNDISPATCHED) {
            inputMutex.lock()
            try {
                if(token==generation && client===activeClient) {
                    val size=(state.value as? ScrcpyState.Streaming)?.videoSize ?: return@launch
                    withContext(Dispatchers.IO){client.injectTouch(action,pointerId,(x*size.width).toInt(),(y*size.height).toInt(),pressure)}
                }
            } catch(error:CancellationException){throw error}
            catch(error:Exception){publish(token,ScrcpyState.Failed(error));activeClient=null;job?.cancel()}
            finally { inputMutex.unlock() }
        }
    }
    @Synchronized private fun publish(token:Long,value:ScrcpyState){
        if(token!=generation)return
        val failed=value is ScrcpyState.Failed && mutable.value !is ScrcpyState.Failed
        mutable.value=value
        if(failed)retired.update{it+1}
    }
    @Synchronized fun stop(){
        generation++
        activeClient=null
        val closing=job;job=null
        val active=mutable.value !is ScrcpyState.Idle && mutable.value !is ScrcpyState.Failed
        mutable.value=ScrcpyState.Idle
        if(active)retired.update{it+1}
        closing?.cancel()
    }
}
