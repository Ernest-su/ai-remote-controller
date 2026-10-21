package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class FileMediaController(private val scope: CoroutineScope, private val captureRequest: (() -> (suspend (String,JsonObject)->String))? = null, private val request: suspend (String,JsonObject)->String) {
    constructor(scope: CoroutineScope, runtime: RuntimeConnection):this(scope, {
        val target=runtime.transferTarget()
        val bound:suspend (String,JsonObject)->String={tool,args->runtime.requestAt(target,tool,args)}
        bound
    }, {tool,args->runtime.request("tool",tool,args)})
    private val mutable=MutableStateFlow(FileMediaState())
    val state=mutable.asStateFlow()
    private val json=Json {ignoreUnknownKeys=true}
    private var generation=0L
    private var job:Job?=null
    fun clear() {generation++;job?.cancel();mutable.value=FileMediaState()}
    fun choose(packageName: String) {if(!state.value.busy && packageName in state.value.players?.packages.orEmpty()) mutable.update {it.copy(selectedPackage=packageName)}}
    private fun run(available: Boolean, operation: suspend (suspend (String,JsonObject)->String)->Unit) {
        if(!available || state.value.busy)return
        val token=generation
        mutable.update {it.copy(noticeId=java.util.UUID.randomUUID().toString(),busy=true,error=null,message=null)}
        job=scope.launch {
            try {operation(captureRequest?.invoke() ?: request)}
            catch(e:CancellationException){throw e}
            catch(e:Exception){if(token==generation)mutable.update {it.copy(error=e.message ?: "媒体操作失败")}}
            finally {if(token==generation)mutable.update {it.copy(busy=false)}}
        }
    }
    fun inspect(root:String,path:String,available:Boolean,localCopy:Boolean=false)=run(available) { request ->
        val token=generation
        mutable.update {it.copy(players=null,selectedPackage=null,localCopy=localCopy)}
        val players=json.decodeFromString<TargetMediaPlayers>(request("media.players",buildJsonObject {put("root",root);put("path",path);if(localCopy)put("transport","local-copy")}))
        check(players.root==root && players.path==path && players.packages.size<=1024){"播放器响应不匹配"}
        check(players.packages.distinct().size==players.packages.size && players.packages.all { name ->
            name.length<=255 && '.' in name && name.split('.').all { part ->
                part.isNotEmpty() && part.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it=='_' }
            }
        }) { "播放器候选包无效" }
        if(token==generation) mutable.update {it.copy(players=players,selectedPackage=players.packages.firstOrNull(),message=if(players.packages.isEmpty())"目标没有支持此文件来源的播放器" else null)}
    }
    fun open(available:Boolean) {
        val players=state.value.players ?: return
        val packageName=state.value.selectedPackage ?: return
        run(available) { request ->
            val token=generation
            val previousResource=state.value.resourceId
            val previousCopy=state.value.publicationId
            var created:MediaPublication?=null
            try {
            if(state.value.localCopy) created=publish(players,token,request)
            val playbackRoot=created?.root ?: players.root
            val playbackPath=created?.path ?: players.path
            val opened=json.decodeFromString<TargetMediaOpen>(request("media.open",buildJsonObject {put("root",playbackRoot);put("path",playbackPath);put("mimeType",players.mimeType);put("package",packageName)}))
            check(opened.root==playbackRoot && opened.path==playbackPath && opened.launched){"目标播放器未启动"}
            check(opened.packageName==packageName && opened.mimeType==players.mimeType && opened.transport==if(created!=null) "file" else players.transport){"播放器启动响应对象不匹配"}
            if(token==generation) {
                mutable.update {it.copy(resourceId=opened.resourceId,publicationId=created?.id,message="目标播放器已启动，实际播放状态请查看目标画面。")}
                if(previousResource!=null && previousResource!=opened.resourceId) runCatching {releaseResource(request,previousResource)}
                if(previousCopy!=null && previousCopy!=created?.id) runCatching {releaseResource(request,previousCopy)}
            }
            } catch(e:Exception) {
                created?.let {copy -> withContext(NonCancellable) {withTimeoutOrNull(5000){runCatching {releaseResource(request,copy.id)}}} }
                throw e
            } finally {if(token==generation)mutable.update {it.copy(preparingCopy=false)}}
        }
    }
    private suspend fun publish(players:TargetMediaPlayers,token:Long,request:suspend (String,JsonObject)->String):MediaPublication {
        mutable.update {it.copy(preparingCopy=true,bytes=0,total=players.sizeBytes)}
        var task=json.decodeFromString<MediaPublicationTask>(request("media.publish",buildJsonObject {put("root",players.root);put("path",players.path)}))
        val taskId=task.id
        try {
            while(true) {
                validateTask(task,taskId,players)
                if(task.phase in listOf("completed","failed","cancelled"))break
                if(token==generation)mutable.update {it.copy(bytes=task.bytesTransferred,total=task.sizeBytes)}
                delay(300)
                task=json.decodeFromString<MediaPublicationTask>(request("task.progress",buildJsonObject {put("id",taskId)}))
            }
            check(task.phase=="completed"){task.error ?: "准备播放副本未完成"}
            val copy=checkNotNull(task.result){"播放副本结果缺失"}
            validateCopy(copy,players,task.sizeBytes)
            return copy
        } catch(e:Exception) {
            withContext(NonCancellable) {withTimeoutOrNull(5000) {runCatching {
                val stopped=json.decodeFromString<MediaPublicationTask>(request("task.cancel",buildJsonObject {put("id",taskId)}))
                validateTask(stopped,taskId,players)
                stopped.result?.let {copy -> validateCopy(copy,players,stopped.sizeBytes);releaseResource(request,copy.id)}
            }}}
            throw e
        }
    }
    private fun validateTask(task:MediaPublicationTask,id:String,players:TargetMediaPlayers) {
        check(task.id==id && task.direction=="media_publish" && task.root==players.root && task.name==players.path && task.unit=="bytes"){"播放副本任务对象不匹配"}
        check(task.sizeBytes in 0..1024L*1024*1024*1024 && task.bytesTransferred in 0..task.sizeBytes){"播放副本进度无效"}
        if(players.sizeBytes!=null)check(task.sizeBytes==players.sizeBytes){"源文件大小已改变，请重新选择"}
    }
    private fun validateCopy(copy:MediaPublication,players:TargetMediaPlayers,size:Long) {
        val name=players.path.substringAfterLast('/')
        check(copy.root=="downloads" && copy.id.matches(Regex("[a-f0-9-]{36}")) && validTargetFileName(name) &&
            copy.path==".airc-media-${copy.id}/$name" && copy.sha256.matches(Regex("[a-f0-9]{64}")) && copy.sizeBytes==size){"播放副本描述无效"}
    }
    private suspend fun releaseResource(request:suspend (String,JsonObject)->String,id:String) {
        val result=json.parseToJsonElement(request("media.release",buildJsonObject {put("id",id)})).jsonObject
        check(result["released"]?.jsonPrimitive?.boolean==true){"播放资源释放未确认，请核对目标状态"}
    }
    fun recover(available:Boolean)=run(available) { request ->
        val token=generation
        val copies=json.decodeFromString<List<MediaPublication>>(request("media.publications",buildJsonObject {}))
        check(copies.size<=4 && copies.map {it.id}.distinct().size==copies.size && copies.all {it.root=="downloads" && it.id.matches(Regex("[a-f0-9-]{36}")) && it.path==".airc-media-${it.id}/${it.path.substringAfterLast('/')}" && validTargetFileName(it.path.substringAfterLast('/')) && it.sizeBytes in 0..1024L*1024*1024*1024 && it.sha256.matches(Regex("[a-f0-9]{64}"))}){"副本列表描述无效"}
        if(token==generation)mutable.update {it.copy(retainedCopies=copies,message=if(copies.isEmpty())"没有保留的播放副本" else "已读取保留的播放副本，可释放后重新投送。")}
    }
    fun releaseCopy(id:String,available:Boolean) {
        if(state.value.retainedCopies.none {it.id==id})return
        run(available) { request ->
            val token=generation
            releaseResource(request,id)
            if(token==generation)mutable.update {it.copy(retainedCopies=it.retainedCopies.filterNot {copy->copy.id==id},publicationId=if(it.publicationId==id)null else it.publicationId,message="播放副本已释放，原文件保留。")}
        }
    }
    fun cancel(){job?.cancel()}
    fun release(available:Boolean) {
        val id=state.value.publicationId ?: state.value.resourceId ?: return
        run(available) { request ->
            val token=generation
            releaseResource(request,id)
            if(token==generation)mutable.update {it.copy(resourceId=null,publicationId=null,retainedCopies=it.retainedCopies.filterNot {copy->copy.id==id},message="播放资源已释放，原文件保留。")}
        }
    }
}
