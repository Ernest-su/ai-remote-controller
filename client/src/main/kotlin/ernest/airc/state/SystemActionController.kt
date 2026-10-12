package ernest.airc
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class SystemActionController(private val scope:CoroutineScope,private val runtime:RuntimeConnection,private val beforeSubmit:()->Unit={}) {
    private val mutable=MutableStateFlow(SystemActionState());val state=mutable.asStateFlow()
    private val json=Json{ignoreUnknownKeys=true}
    private val lock=Any()
    private fun update(token:Long,change:(SystemActionState)->SystemActionState)=synchronized(lock){if(token==generation)mutable.update(change)}
    private var generation=0L;private var job:Job?=null;private var approval:CompletableDeferred<Boolean>?=null
    fun clear(){synchronized(lock){
        generation++;job?.cancel();approval=null
        mutable.update { previous ->
            if(previous.requiresVerification) SystemActionState(
                message="先前目标的系统操作可能已提交，请核对该设备实际状态；连接变化不能证明成功。",
                requiresVerification=true)
            else SystemActionState()
        }
    }
    }
    fun confirm(accepted:Boolean){synchronized(lock){approval?.complete(accepted)}}
    fun prepare(action:String,available:Boolean)=run(available,action)
    fun pending(available:Boolean)=run(available,null)
    private fun run(available:Boolean,action:String?){synchronized(lock){
        if(!available||state.value.busy)return
        val target=try{runtime.transferTarget()}catch(_:IllegalStateException){
            mutable.update{it.copy(error="请先连接设备，再准备系统操作。")}
            return
        }
        val token=generation
        mutable.value=SystemActionState(busy=true)
        val task=scope.launch(start=CoroutineStart.LAZY){
            var proposal:SystemActionProposal?=null;var submitted=false
            try {
                val payload=runtime.requestAt(target,if(action==null)"device.system_pending" else "device.system_action",buildJsonObject{action?.let{put("action",it)}})
                val p=if(action==null)json.decodeFromString<List<SystemActionProposal>>(payload).firstOrNull() ?: error("没有待确认的系统操作") else json.decodeFromString<SystemActionProposal>(payload)
                check(validSystemProposal(p) && (action==null||p.action==action)){"系统操作提案无效"}
                proposal=p
                val pending=CompletableDeferred<Boolean>()
                synchronized(lock){
                    if(token!=generation)throw CancellationException("系统操作已取消")
                    approval=pending;mutable.update{it.copy(proposal=p)}
                }
                if(!withTimeout(p.expiresInSeconds*1000L){pending.await()})return@launch
                currentCoroutineContext().ensureActive()
                runtime.checkTransferTarget(target)
                beforeSubmit()
                submitted=true
                update(token){it.copy(proposal=null,requiresVerification=true,message="正在提交；连接中断不代表操作成功。")}
                val result=json.parseToJsonElement(runtime.approveSystemAt(target,buildJsonObject{put("id",p.id);put("accepted",true)})).jsonObject
                check(result["id"]?.jsonPrimitive?.content==p.id && result["action"]?.jsonPrimitive?.content==p.action && result["submitted"]?.jsonPrimitive?.boolean==true && result["completed"]?.jsonPrimitive?.boolean==false){"系统操作提交结果无效"}
                update(token){it.copy(message="${systemActionLabel(p.action)}请求已提交，请核对目标实际状态。")}
            }catch(e:CancellationException){if(e is TimeoutCancellationException)update(token){it.copy(error="确认已过期，请重新准备操作。")};throw e}
            catch(e:Exception){update(token){it.copy(error=if(submitted)"未能确认提交结果，请核对目标状态；不要立即重复操作。" else e.message ?: "系统操作失败")}}
            finally{
                if(!submitted)proposal?.let{p->withContext(NonCancellable){withTimeoutOrNull(5000){runCatching{runtime.approveSystemAt(target,buildJsonObject{put("id",p.id);put("accepted",false)})}}}}
                synchronized(lock){if(token==generation){approval=null;mutable.update{it.copy(busy=false,proposal=null)}}}
            }
        }
        job=task;task.start()
    }}
}
