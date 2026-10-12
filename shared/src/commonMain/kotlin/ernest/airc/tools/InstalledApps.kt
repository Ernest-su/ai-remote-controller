package ernest.airc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class InstalledApp(val packageName: String, val label: String? = null, val system: Boolean? = null)

@Serializable
private data class AppListEntry(val `package`: String, val label: String? = null, val system: Boolean? = null)

@Serializable
private data class AppListResult(val apps: List<AppListEntry>)

private val appListJson = Json { ignoreUnknownKeys = true }

/** Older runtimes return pm text; metadata stays unknown rather than invented. */
fun decodeInstalledApps(output: String): List<InstalledApp> {
    if (output.trimStart().startsWith("{")) {
        return appListJson.decodeFromString<AppListResult>(output).apps.map {
            InstalledApp(it.`package`, it.label, it.system)
        }
    }
    return output.lineSequence().filter { it.isNotBlank() }.map { line ->
        require(line.startsWith("package:")) { "无法解析应用列表，请更新目标服务" }
        InstalledApp(line.removePrefix("package:").trim())
    }.toList()
}

fun filterInstalledApps(apps: List<InstalledApp>, query: String): List<InstalledApp> {
    val term = query.trim()
    return apps.filter { term.isEmpty() || it.packageName.contains(term, ignoreCase = true) ||
        it.label?.contains(term, ignoreCase = true) == true }
}
