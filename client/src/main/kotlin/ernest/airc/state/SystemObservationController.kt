package ernest.airc
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
class SystemObservationController(private val scope:CoroutineScope,private val query:suspend()->String) {
    constructor(scope:CoroutineScope,runtime:RuntimeConnection):this(scope,{
        val target=runtime.transferTarget()
        val payload=runtime.requestAt(target,"device.system_state",buildJsonObject{})
        runtime.checkTransferTarget(target);payload
    })
    private val mutable=MutableStateFlow(SystemObservationState());val state=mutable.asStateFlow()
    private val json=Json{ignoreUnknownKeys=true}
    private val lock=Any()
    private var generation=0L;private var job:Job?=null
    private fun update(token:Long,transform:(SystemObservationState)->SystemObservationState)=synchronized(lock){if(token==generation)mutable.update(transform)}
    fun clear()=synchronized(lock){generation++;job?.cancel();mutable.value=SystemObservationState()}
    fun refresh(available:Boolean):Unit=synchronized(lock){
        if(!available||state.value.loading)return@synchronized
        val token=generation;mutable.value=SystemObservationState(loading=true)
        val pending=scope.launch(start=CoroutineStart.LAZY){
            try{
                val observation=json.decodeFromString<TargetSystemState>(query())
                check(validSystemObservation(observation)){"系统状态报告无效"}
                update(token){SystemObservationState(observation=observation,receivedAt=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",java.util.Locale.ROOT).format(java.util.Date()))}
            }catch(e:CancellationException){throw e}
            catch(e:Exception){update(token){SystemObservationState(error=e.message ?: "查询系统状态失败")}}
            finally{update(token){it.copy(loading=false)}}
        }
        job=pending;pending.start();Unit
    }
}
