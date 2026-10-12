package ernest.airc
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class AppCacheController(private val scope:CoroutineScope,private val runtime:RuntimeConnection) {
    private val mutable=MutableStateFlow(AppCacheState());val state=mutable.asStateFlow()
    private val json=Json{ignoreUnknownKeys=true}
    private var generation=0L;private var job:Job?=null;private var approval:CompletableDeferred<Boolean>?=null
    fun clear(){generation++;job?.cancel();mutable.value=AppCacheState()}
    fun confirm(accepted:Boolean){approval?.complete(accepted)}
    private fun run(available:Boolean,operation:suspend(Long,RuntimeTransferTarget)->Unit){
        if(!available||state.value.busy)return
        val token=generation;mutable.update{it.copy(busy=true,error=null,message=null)}
        job=scope.launch{try{operation(token,runtime.transferTarget())}catch(e:CancellationException){throw e}catch(e:Exception){if(token==generation)mutable.update{it.copy(error=e.message ?: "应用操作失败")}}finally{if(token==generation)mutable.update{it.copy(busy=false,proposal=null)}}}
    }
    fun prepare(packageName:String,available:Boolean,onComplete:()->Unit={})=run(available){token,target->
        val proposal=json.decodeFromString<AppCacheProposal>(runtime.requestAt(target,"apps.clear_cache",buildJsonObject{put("package",packageName)}))
        check(proposal.app.packageName==packageName){"缓存清理确认对象不匹配"}
        executeProposal(proposal,token,target,onComplete)
    }
    fun pending(available:Boolean,onComplete:()->Unit={})=run(available){token,target->
        val proposals=json.decodeFromString<List<AppCacheProposal>>(runtime.requestAt(target,"apps.cache_pending",JsonObject(emptyMap())))
        executeProposal(proposals.firstOrNull() ?: error("没有待确认的缓存清理"),token,target,onComplete)
    }
    private suspend fun executeProposal(proposal:AppCacheProposal,token:Long,target:RuntimeTransferTarget,onComplete:()->Unit){
        check(proposal.kind=="app_cache_clear"&&proposal.approvalRequired&&proposal.cacheOnly&&proposal.mode=="package_manager_cache_only"&&!proposal.app.system&&proposal.app.appId>=10000){"缓存清理提案无效"}
        runtime.checkTransferTarget(target);val pending=CompletableDeferred<Boolean>();approval=pending
        if(token==generation)mutable.update{it.copy(proposal=proposal,phase="awaiting_approval")}
        var submitted=false
        try{
            if(!pending.await())return
            runtime.checkTransferTarget(target);submitted=true
            var task=json.decodeFromString<TransferProgress>(runtime.approveCacheAt(target,buildJsonObject{put("id",proposal.id)}))
            val taskId=task.id
            if(token==generation)mutable.update{it.copy(proposal=null,phase="committing")}
            while(true){
                check(task.id==taskId && task.direction=="app_cache_clear" && task.root=="apps" && task.name==proposal.app.packageName && task.unit=="items"){"缓存清理任务对象不匹配"}
                if(task.phase in listOf("completed","failed","cancelled"))break
                delay(300);runtime.checkTransferTarget(target)
                task=json.decodeFromString<TransferProgress>(runtime.requestAt(target,"task.progress",buildJsonObject{put("id",taskId)}))
            }
            check(task.phase=="completed"){task.error ?: "缓存清理未完成"}
            val result=checkNotNull(task.result).jsonObject
            check(result["package"]?.jsonPrimitive?.content==proposal.app.packageName && result["userId"]?.jsonPrimitive?.int==proposal.app.userId && result["cacheOnly"]?.jsonPrimitive?.boolean==true && result["completed"]?.jsonPrimitive?.boolean==true){"缓存清理结果对象不匹配"}
            if(token==generation){mutable.update{it.copy(phase="completed",message="系统已报告缓存清理完成：${proposal.app.packageName}（用户 ${proposal.app.userId}）。未测量释放空间，请核对目标状态。")};onComplete()}
        }finally{
            if(!submitted)withContext(NonCancellable){withTimeoutOrNull(5000){runCatching{runtime.checkTransferTarget(target);runtime.approveCacheAt(target,buildJsonObject{put("id",proposal.id);put("accepted",false)})}}}
            if(approval===pending)approval=null
        }
    }
}
