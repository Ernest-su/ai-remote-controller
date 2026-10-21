package ernest.airc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local selection never replaces an in-flight transfer or a verified download cache. */
class UploadDraftController {
    private val mutable = MutableStateFlow(emptyList<UploadSource>())
    val state = mutable.asStateFlow()
    fun select(sources: List<UploadSource>) {
        require(sources.size in 1..32 && sources.all { validTargetFileName(it.name) } && sources.map { it.name }.distinct().size == sources.size) { "最多32个文件，名称不能重复" }
        mutable.value = sources.toList()
    }
}
