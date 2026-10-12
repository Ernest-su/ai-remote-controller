package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps credentials only in ViewModel memory and the encrypted store, never saved state. */
class ModelProfileController(
    private val store: ModelProfileStore,
    private val catalog: ModelCatalog,
    private val scope: CoroutineScope,
) : ModelProfileActions {
    private var snapshot = ModelProfileSnapshot()
    private var readable = true
    private val writes = Mutex()
    private var revision = 0L
    private var draftRevision = 0L
    private var fetchJob: Job? = null
    private val mutable = MutableStateFlow(ModelProfileState())
    override val state = mutable.asStateFlow()
    init {
        try {
            snapshot = store.read()
            mutable.value = ModelProfileState(snapshot.profiles,
                snapshot.profiles.find { it.baseUrl == snapshot.selectedUrl } ?: ModelProfile())
        } catch (_: Exception) {
            readable = false
            mutable.value = ModelProfileState(message = "无法读取加密配置，原存储未覆盖")
        }
    }

    fun close() { fetchJob?.cancel(); (catalog as? AutoCloseable)?.close() }

    override fun editUrl(value: String) = edit { it.copy(baseUrl = value, models = emptyList(), fetchedAt = null) }
    override fun editKey(value: String) = edit { it.copy(apiKey = value, models = emptyList(), fetchedAt = null) }
    override fun editModel(value: String) { draftRevision++; mutable.update { it.copy(draft = it.draft.copy(model = value), message = "") } }
    private fun edit(change: (ModelProfile) -> ModelProfile) {
        revision++; draftRevision++; fetchJob?.cancel()
        mutable.update { it.copy(draft = change(it.draft), fetching = false, message = "") }
    }

    private suspend fun persist(change: (ModelProfileSnapshot) -> ModelProfileSnapshot) = writes.withLock {
        check(readable) { "配置存储不可用" }
        val next = change(snapshot)
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { store.write(next) }
            snapshot = next
            mutable.update { it.copy(profiles = next.profiles) }
        }
    }

    override fun select(url: String?) {
        val selectedUrl = if(url == null) null else try { canonicalModelUrl(url) }
        catch (_: IllegalArgumentException) { mutable.update { it.copy(message = "API 地址无效") }; return }
        edit { snapshot.profiles.find { it.baseUrl == selectedUrl } ?: ModelProfile() }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { persist { it.copy(selectedUrl = selectedUrl) } }
            catch (_: Exception) { mutable.update { it.copy(message = "无法保存当前配置选择") } }
        }
    }

    override fun delete(url: String) {
        val deletedUrl = try { canonicalModelUrl(url) }
        catch (_: IllegalArgumentException) { mutable.update { it.copy(message = "API 地址无效") }; return }
        val draftUrl = runCatching { canonicalModelUrl(state.value.draft.baseUrl) }.getOrNull()
        if (draftUrl == deletedUrl) edit { ModelProfile() }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                persist { it.copy(profiles = it.profiles.filterNot { profile -> profile.baseUrl == deletedUrl },
                    selectedUrl = it.selectedUrl.takeUnless { selected -> selected == deletedUrl }) }
                mutable.update { it.copy(message = "已删除本地配置与模型列表，目标设备配置未改变") }
            } catch (_: Exception) { mutable.update { it.copy(message = "删除配置失败") } }
        }
    }

    private suspend fun save(profile: ModelProfile) = persist {
        it.copy(profiles = it.profiles.filterNot { old -> old.baseUrl == profile.baseUrl } + profile, selectedUrl = profile.baseUrl)
    }

    override fun save() {
        val profile = try { state.value.draft.validated() }
        catch (e: IllegalArgumentException) { mutable.update { it.copy(message = e.message ?: "配置无效") }; return }
        val token = draftRevision
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                save(profile)
                if (draftRevision == token) mutable.update { it.copy(draft = profile, message = "已保存到 App") }
            } catch (_: Exception) { mutable.update { it.copy(message = "保存配置失败") } }
        }
    }

    override fun fetch() {
        if (state.value.fetching) return
        val profile = try { state.value.draft.validated() }
        catch (e: IllegalArgumentException) { mutable.update { it.copy(message = e.message ?: "配置无效") }; return }
        val token = revision
        mutable.update { it.copy(fetching = true, message = "正在拉取模型列表…") }
        fetchJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val models = catalog.fetch(profile)
                if (revision != token) return@launch
                val updated = profile.copy(model = state.value.draft.model, models = models, fetchedAt = System.currentTimeMillis())
                save(updated)
                if (revision == token) mutable.update { it.copy(draft = updated.copy(model = it.draft.model), message = "已缓存 ${models.size} 个模型") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (revision == token) mutable.update { it.copy(message = "拉取模型列表失败；已有缓存保留，可手填模型或重试") }
            } finally {
                if (revision == token) mutable.update { it.copy(fetching = false) }
            }
        }
    }

    fun sync(connected: Boolean, send: suspend (ModelProfile) -> Unit) {
        if (!connected || state.value.syncing) return
        val profile = try { state.value.draft.validated(requireModel = true) }
        catch (e: IllegalArgumentException) { mutable.update { it.copy(message = e.message ?: "配置无效") }; return }
        val token = revision
        mutable.update { it.copy(syncing = true, message = "正在更新目标设备…") }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { save(profile); send(profile); if (revision == token) mutable.update { it.copy(message = "已更新到目标设备") } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (revision == token) mutable.update { it.copy(message = "更新目标设备失败，本地配置保留，请连接后重试") } }
            finally { mutable.update { it.copy(syncing = false) } }
        }
    }
}
