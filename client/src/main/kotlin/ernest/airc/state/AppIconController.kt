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
    private val cache = HashMap<Pair<RuntimeTransferTarget,String>, ByteArray>()
    private var versions = emptyMap<String, String?>()
    private var generation = 0L
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private var observedSession = runtime.session.value
    init { scope.launch { runtime.session.collectLatest { session ->
        if(session != observedSession) { observedSession = session; clear() }
    } } }
    fun clear() = synchronized(cache) { generation++; cache.clear(); versions = emptyMap(); mutableRevision.value = generation }
    /** Retain images only for unchanged entries in the latest installed-app snapshot. */
    fun updateApps(apps: List<InstalledApp>) = synchronized(cache) {
        val next = apps.associate { it.packageName to it.cacheKey }
        generation++
        cache.keys.removeAll { (_, name) -> next[name] == null || versions[name] != next[name] }
        versions = next
        mutableRevision.value = generation
    }
    fun cached(packageName: String): ByteArray? {
        val target = runCatching { runtime.transferTarget() }.getOrNull() ?: return null
        return synchronized(cache) { cache[target to packageName] }
    }
    suspend fun load(packageName: String): ByteArray {
        val target = runtime.transferTarget()
        val token = synchronized(cache) { generation }
        val expected = synchronized(cache) { versions[packageName] }
        // Returning to a loaded row must not wait behind uncached, slow requests.
        synchronized(cache) { cache[target to packageName] }?.let { return it }
        return gate.withPermit {
            currentCoroutineContext().ensureActive()
            runtime.checkTransferTarget(target)
            synchronized(cache) {
                check(token == generation) { "图标列表已刷新" }
                cache[target to packageName]
            }?.let { return@withPermit it }
            val bytes = runtime.appIcon(target, packageName, expected) {
                synchronized(cache) { check(token == generation) { "图标列表已刷新" } }
            }
            currentCoroutineContext().ensureActive()
            synchronized(cache) {
                check(token == generation) { "目标连接已改变" }
                cache[target to packageName] = bytes
            }
            bytes
        }
    }
}
