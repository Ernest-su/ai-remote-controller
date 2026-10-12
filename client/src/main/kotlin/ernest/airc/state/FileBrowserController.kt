package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class FileBrowserController(private val scope: CoroutineScope, private val runtime: RuntimeConnection? = null, private val request: suspend (String, JsonObject) -> String) {
    constructor(scope:CoroutineScope,runtime:RuntimeConnection):this(scope,runtime,{tool,args->runtime.request("tool",tool,args)})
    fun createDirectory(name:String,available:Boolean) {
        if(!available || state.value.loading || !validTargetFileName(name))return
        val token=generation;val root=state.value.root;val parent=state.value.path
        val path=listOf(parent,name).filter{it.isNotEmpty()}.joinToString("/")
        mutable.update{it.copy(loading=true,error=null)}
        launchOperation {
            try {
                val target=runtime?.transferTarget()
                val args=buildJsonObject{put("root",root);put("path",path)}
                val payload=if(runtime!=null)runtime.requestAt(checkNotNull(target),"files.mkdir",args)else request("files.mkdir",args)
                if(runtime!=null)runtime.checkTransferTarget(checkNotNull(target))
                val result=json.parseToJsonElement(payload).jsonObject
                check(result["root"]?.jsonPrimitive?.content==root && result["path"]?.jsonPrimitive?.content==path && result["created"]?.jsonPrimitive?.boolean==true){"目录创建响应不匹配"}
                if(token==generation){mutable.update{it.copy(loading=false)};load(parent,available)}
            }catch(e:CancellationException){if(token==generation)mutable.update{it.copy(loading=false)};throw e}
            catch(e:Exception){if(token==generation)mutable.update{it.copy(loading=false,error=e.message ?: "创建目录失败")}}
        }
    }
    private suspend fun query(tool:String,args:JsonObject):String {
        if(runtime==null)return request(tool,args)
        val target=runtime.transferTarget()
        val result=runtime.requestAt(target,tool,args)
        runtime.checkTransferTarget(target)
        return result
    }
    private val mutable = MutableStateFlow(FileBrowserState())
    val state = mutable.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private var generation = 0L
    private var job: Job? = null
    private fun launchOperation(action:suspend CoroutineScope.()->Unit) {
        val next=scope.launch(start=CoroutineStart.LAZY,block=action)
        job=next
        next.start()
    }
    fun clear() { generation++; job?.cancel(); job = null; mutable.value = FileBrowserState() }
    fun selectRoot(root: String) {
        if (root !in listOf("downloads","inbox","full") || state.value.loading || root == state.value.root) return
        clear(); mutable.value = FileBrowserState(root = root)
    }
    /** Breadcrumbs may only navigate to an ancestor inside the current root. */
    fun ancestor(path: String, available: Boolean) {
        val current = state.value.path
        if (path == current || (path.isNotEmpty() && !current.startsWith("$path/"))) return
        if (path.isNotEmpty() && path.split('/').any { !validTargetFileName(it) }) return
        load(path, available)
    }
    fun refresh(available: Boolean) = load(state.value.path, available)
    fun parent(available: Boolean) { if (state.value.path.isNotEmpty()) load(state.value.path.substringBeforeLast('/', ""), available) }
    fun enter(entry: TargetFileEntry, available: Boolean) {
        if (entry.kind != "directory" || !validTargetFileName(entry.name)) return
        val path = listOf(state.value.path, entry.name).filter { it.isNotEmpty() }.joinToString("/")
        load(path, available)
    }
    fun more(available: Boolean) { if (state.value.nextCursor != null) load(state.value.path, available, true) }
    private fun compareNames(left:String,right:String):Int {
        val a=left.encodeToByteArray();val b=right.encodeToByteArray()
        for(index in 0 until minOf(a.size,b.size)) {
            val order=(a[index].toInt() and 255).compareTo(b[index].toInt() and 255)
            if(order!=0)return order
        }
        return a.size.compareTo(b.size)
    }
    private fun load(path: String, available: Boolean, append: Boolean = false) {
        if (!available || state.value.loading) return
        val cursor = if (append) state.value.nextCursor else null
        val token = generation
        val root = state.value.root
        mutable.update { it.copy(loading = true, error = null, selected = null) }
        launchOperation {
            try {
                val response = query("files.list", buildJsonObject { put("root",root); put("path", path); put("limit", 50); cursor?.let { put("cursor", it) } })
                val page = json.decodeFromString<TargetFilesPage>(response)
                check(page.root == root && page.path == path && page.entries.size <= 50) { "文件列表响应与请求不匹配" }
                check(page.entries.all { entry ->
                    validTargetFileName(entry.name) && entry.kind in listOf("file", "directory", "symlink", "other") &&
                        (entry.sizeBytes?.let { it >= 0 } ?: true)
                } && page.entries.map { it.name }.distinct().size == page.entries.size) { "文件列表条目无效" }
                check(page.entries.zipWithNext().all { (a,b) -> compareNames(a.name,b.name)<0 } &&
                    (cursor==null || page.entries.all { compareNames(it.name,cursor)>0 })) { "目录分页顺序无效，请刷新目录" }
                val nextCursor = page.nextCursor
                check(nextCursor == null || (validTargetFileName(nextCursor) && nextCursor != cursor && nextCursor == page.entries.lastOrNull()?.name)) { "无效分页游标" }
                if (token == generation) mutable.update {
                    val entries = if (append) (it.entries + page.entries) else page.entries
                    check(entries.size <= 4096) { "目录缓存超过 4096 项，请选择更小目录" }
                    FileBrowserState(path, entries, page.nextCursor, loaded = true, root = root)
                }
            } catch (e: CancellationException) {
                if (token == generation) mutable.update { it.copy(loading = false) }
                throw e
            }
            catch (e: Exception) { if (token == generation) mutable.update { it.copy(loading = false, error = e.message ?: "读取目录失败") } }
        }
    }
    fun inspect(entry: TargetFileEntry, available: Boolean) {
        if (!available || state.value.loading || entry.kind !in listOf("file", "directory") || !validTargetFileName(entry.name)) return
        val path = listOf(state.value.path, entry.name).filter { it.isNotEmpty() }.joinToString("/")
        val token = generation
        val root = state.value.root
        mutable.update { it.copy(loading = true, error = null, selected = null) }
        launchOperation {
            try {
                val result = json.decodeFromString<TargetFileStat>(query("files.stat", buildJsonObject { put("root",root); put("path", path) }))
                check(result.root == root && result.path == path && result.kind == entry.kind &&
                    (result.sizeBytes?.let { it >= 0 } ?: true)) { "文件属性响应与请求不匹配" }
                if (token == generation) mutable.update { it.copy(loading = false, selected = result) }
            } catch (e: CancellationException) {
                if (token == generation) mutable.update { it.copy(loading = false) }
                throw e
            }
            catch (e: Exception) { if (token == generation) mutable.update { it.copy(loading = false, error = e.message ?: "读取属性失败") } }
        }
    }
}
