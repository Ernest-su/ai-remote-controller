package ernest.airc

import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.StateFlow

@Serializable
data class ModelProfile(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val models: List<String> = emptyList(),
    val fetchedAt: Long? = null,
) {
    override fun toString() = "ModelProfile(baseUrl=$baseUrl, model=$model, apiKey=<redacted>)"
}

@Serializable
data class ModelProfileSnapshot(val profiles: List<ModelProfile> = emptyList(), val selectedUrl: String? = null)

interface ModelProfileStore {
    fun read(): ModelProfileSnapshot
    fun write(snapshot: ModelProfileSnapshot)
}

data class ModelProfileState(
    val profiles: List<ModelProfile> = emptyList(),
    val draft: ModelProfile = ModelProfile(),
    val fetching: Boolean = false,
    val syncing: Boolean = false,
    val message: String = "",
)

fun interface ModelCatalog {
    suspend fun fetch(profile: ModelProfile): List<String>
}

interface ModelProfileActions {
    val state: StateFlow<ModelProfileState>
    fun editUrl(value: String)
    fun editKey(value: String)
    fun editModel(value: String)
    fun select(url: String?)
    fun delete(url: String)
    fun save()
    fun fetch()
}
