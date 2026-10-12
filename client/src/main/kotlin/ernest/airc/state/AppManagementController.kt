package ernest.airc
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class AppManagementController(private val scope:CoroutineScope,private val runtime:RuntimeConnection) {
    private val mutable=MutableStateFlow(AppManagementState());val state=mutable.asStateFlow()
    private val json=Json{ignoreUnknownKeys=true}
    private var generation=0L;private var job:Job?=null;private var approval:CompletableDeferred<Boolean>?=null
    fun clear(){generation++;job?.cancel();mutable.value=AppManagementState()}
    fun confirm(accepted:Boolean){approval?.complete(accepted)}
    private fun run(available:Boolean,operation:suspend(Long,RuntimeTransferTarget)->Unit){
        if(!available||state.value.busy)return
        val token=generation;mutable.update{it.copy(busy=true,error=null,message=null)}
        job=scope.launch{try{operation(token,runtime.transferTarget())}catch(e:CancellationException){throw e}catch(e:Exception){if(token==generation)mutable.update{it.copy(error=e.message ?: "应用操作失败")}}finally{if(token==generation)mutable.update{it.copy(busy=false,proposal=null)}}}
    }
    fun inspect(packageName:String,available:Boolean)=run(available){token,target->
        val info=json.decodeFromString<TargetAppInfo>(runtime.requestAt(target,"apps.info",buildJsonObject{put("package",packageName)}))
        check(info.packageName==packageName){"应用信息响应不匹配"}
        if(token==generation)mutable.update{it.copy(info=info)}
    }
    fun uninstall(packageName:String,available:Boolean,onComplete:()->Unit={})=run(available){token,target->
        val proposal=json.decodeFromString<AppUninstallProposal>(runtime.requestAt(target,"apps.uninstall",buildJsonObject{put("package",packageName)}))
        check(proposal.app.packageName==packageName){"卸载确认对象不匹配"}
        executeProposal(proposal,token,target,onComplete)
    }
    fun pending(available:Boolean,onComplete:()->Unit={})=run(available){token,target->
        val proposals=json.decodeFromString<List<AppUninstallProposal>>(runtime.requestAt(target,"apps.pending",JsonObject(emptyMap())))
        executeProposal(proposals.firstOrNull() ?: error("没有待确认的应用卸载"),token,target,onComplete)
    }
    private suspend fun executeProposal(proposal:AppUninstallProposal,token:Long,target:RuntimeTransferTarget,onComplete:()->Unit){
        check(proposal.kind=="app_uninstall"&&proposal.approvalRequired&&proposal.dataLoss&&!proposal.app.system&&proposal.app.appId>=10000){"卸载提案无效"}
        runtime.checkTransferTarget(target);val pending=CompletableDeferred<Boolean>();approval=pending
        if(token==generation)mutable.update{it.copy(proposal=proposal,info=proposal.app,phase="awaiting_approval")}
        var submitted=false
        try{
            if(!pending.await())return
            runtime.checkTransferTarget(target);submitted=true
            var task=json.decodeFromString<TransferProgress>(runtime.approveAppAt(target,buildJsonObject{put("id",proposal.id)}))
            if(token==generation)mutable.update{it.copy(proposal=null,phase="committing")}
            val taskId=task.id
            while(true){
                check(task.id==taskId && task.direction=="app_uninstall" && task.root=="apps" && task.name==proposal.app.packageName && task.unit=="items"){"卸载任务对象不匹配"}
                if(task.phase in listOf("completed","failed","cancelled"))break
                delay(300);runtime.checkTransferTarget(target)
                task=json.decodeFromString<TransferProgress>(runtime.requestAt(target,"task.progress",buildJsonObject{put("id",taskId)}))
            }
            check(task.phase=="completed"){task.error ?: "卸载未完成"}
            val result=checkNotNull(task.result).jsonObject
            check(result["package"]?.jsonPrimitive?.content==proposal.app.packageName && result["userId"]?.jsonPrimitive?.int==proposal.app.userId && result["removed"]?.jsonPrimitive?.boolean==true && result["dataRemoved"]?.jsonPrimitive?.boolean==true){"卸载结果对象不匹配"}
            if(token==generation){mutable.update{it.copy(info=null,phase="completed",message="已卸载 ${proposal.app.packageName}（用户 ${proposal.app.userId}），应用数据已删除。")};onComplete()}
        }finally{
            if(!submitted)withContext(NonCancellable){withTimeoutOrNull(5000){runCatching{runtime.checkTransferTarget(target);runtime.approveAppAt(target,buildJsonObject{put("id",proposal.id);put("accepted",false)})}}}
            if(approval===pending)approval=null
        }
    }
}
