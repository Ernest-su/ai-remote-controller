package ernest.airc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class InstalledApp(val packageName: String, val label: String? = null, val system: Boolean? = null, val cacheKey: String? = null)

@Serializable
private data class AppListEntry(val `package`: String, val label: String? = null, val system: Boolean? = null, val cacheKey: String? = null)

@Serializable
private data class AppListResult(val apps: List<AppListEntry>)

private val appListJson = Json { ignoreUnknownKeys = true }

/** A null cache key denotes the runtime's explicit package-manager fallback. */
fun decodeInstalledApps(output: String): List<InstalledApp> =
    appListJson.decodeFromString<AppListResult>(output).apps.map {
        require(it.cacheKey == null || it.cacheKey.matches(Regex("[a-f0-9]{64}"))) { "应用缓存标识无效" }
        InstalledApp(it.`package`, it.label, it.system, it.cacheKey)
    }

fun filterInstalledApps(apps: List<InstalledApp>, query: String): List<InstalledApp> {
    val term = query.trim()
    return apps.filter { term.isEmpty() || it.packageName.contains(term, ignoreCase = true) ||
        it.label?.contains(term, ignoreCase = true) == true }
}
