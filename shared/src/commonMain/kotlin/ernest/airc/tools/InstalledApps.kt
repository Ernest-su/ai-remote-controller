package ernest.airc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class InstalledApp(val packageName: String, val label: String? = null, val system: Boolean? = null, val cacheKey: String? = null, val firstInstallTime: Long? = null, val sizeBytes: Long? = null, val sizeQueried: Boolean = false)

@Serializable
private data class AppListEntry(val `package`: String, val label: String? = null, val system: Boolean? = null, val cacheKey: String? = null, val firstInstallTime: Long? = null, val sizeBytes: Long? = null)

@Serializable
private data class AppListResult(val apps: List<AppListEntry>, val sizesRequested: Boolean = false)

private val appListJson = Json { ignoreUnknownKeys = true }

/** A null cache key denotes the runtime's explicit package-manager fallback. */
fun decodeInstalledApps(output: String): List<InstalledApp> {
    val result = appListJson.decodeFromString<AppListResult>(output)
    return result.apps.map {
        require(it.cacheKey == null || it.cacheKey.matches(Regex("[a-f0-9]{64}"))) { "应用缓存标识无效" }
        require(it.firstInstallTime == null || it.firstInstallTime >= 0) { "应用安装时间无效" }
        require(it.sizeBytes == null || it.sizeBytes >= 0) { "应用大小无效" }
        InstalledApp(it.`package`, it.label, it.system, it.cacheKey, it.firstInstallTime?.takeIf { time -> time > 0 }, it.sizeBytes, result.sizesRequested)
    }
}

enum class AppSort(val label: String, val descendingDefault: Boolean = false) {
    DEFAULT("默认排序"), NAME("名称"), INSTALLED("安装时间", true), SIZE("大小", true)
}

/** Sort the entire filtered list before paging; unknown metadata always sorts last. */
fun sortInstalledApps(apps: List<InstalledApp>, sort: AppSort, descending: Boolean = sort.descendingDefault,
    favorites: List<String> = emptyList()): List<InstalledApp> {
    if (sort == AppSort.DEFAULT) return favoriteFirstApps(apps, favorites)
    val names = Comparator<InstalledApp> { left, right ->
        (left.label?.takeIf(String::isNotBlank) ?: left.packageName)
            .compareTo(right.label?.takeIf(String::isNotBlank) ?: right.packageName, ignoreCase = true)
            .takeIf { it != 0 } ?: left.packageName.compareTo(right.packageName)
    }
    return apps.sortedWith { left, right ->
        if (sort == AppSort.NAME) (if (descending) names.compare(right, left) else names.compare(left, right))
        else {
            val a = if (sort == AppSort.SIZE) left.sizeBytes else left.firstInstallTime
            val b = if (sort == AppSort.SIZE) right.sizeBytes else right.firstInstallTime
            when {
                a == null && b != null -> 1
                a != null && b == null -> -1
                a == null -> names.compare(left, right)
                else -> (if (descending) b!!.compareTo(a) else a.compareTo(b!!)).takeIf { it != 0 } ?: names.compare(left, right)
            }
        }
    }
}

fun filterInstalledApps(apps: List<InstalledApp>, query: String): List<InstalledApp> {
    val term = query.trim()
    return apps.filter { term.isEmpty() || it.packageName.contains(term, ignoreCase = true) ||
        it.label?.contains(term, ignoreCase = true) == true }
}

/** Stable ordering preserves the user's favorite order and the server order for other entries. */
fun favoriteFirstApps(apps: List<InstalledApp>, favorites: List<String>): List<InstalledApp> {
    val order = favorites.withIndex().associate { it.value to it.index }
    return apps.sortedBy { order[it.packageName] ?: Int.MAX_VALUE }
}
