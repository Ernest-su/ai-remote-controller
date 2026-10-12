package ernest.airc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Memory-only cache; every request remains bound to its original runtime connection. */
class AppIconController(scope: CoroutineScope, private val runtime: RuntimeConnection) {
    private val gate = Semaphore(2)
    private val cache = LinkedHashMap<Pair<RuntimeTransferTarget,String>, ByteArray>(64, .75f, true)
    private var generation = 0L
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private var observedSession = runtime.session.value
    init { scope.launch { runtime.session.collectLatest { session ->
        if(session != observedSession) { observedSession = session; clear() }
    } } }
    fun clear() = synchronized(cache) { generation++; cache.clear(); mutableRevision.value = generation }
    suspend fun load(packageName: String): ByteArray {
        val target = runtime.transferTarget()
        val token = synchronized(cache) { generation }
        return gate.withPermit {
            currentCoroutineContext().ensureActive()
            runtime.checkTransferTarget(target)
            synchronized(cache) {
                check(token == generation) { "图标列表已刷新" }
                cache[target to packageName]
            }?.let { return@withPermit it }
            val bytes = runtime.appIcon(target, packageName)
            currentCoroutineContext().ensureActive()
            synchronized(cache) {
                check(token == generation) { "目标连接已改变" }
                cache[target to packageName] = bytes
                while(cache.size > 64) cache.remove(cache.keys.first())
            }
            bytes
        }
    }
}
