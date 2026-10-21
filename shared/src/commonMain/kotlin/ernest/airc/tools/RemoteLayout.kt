package ernest.airc

import kotlinx.serialization.Serializable

val configurableRemoteKeys = routineKeys + listOf("enter", "delete", "channel_up", "channel_down") + (0..9).map { it.toString() }
fun configurableKeyLabel(key: String) = when(key) { "enter" -> "回车"; "delete" -> "删除"; "channel_up" -> "频道 +"; "channel_down" -> "频道 −"; else -> routineKeyLabel(key) }
@Serializable
data class RemoteShortcut(val kind: String, val value: String, val label: String) {
    fun valid() = label.isNotBlank() && label.length <= 40 && when(kind) {
        "key" -> value in configurableRemoteKeys
        "app" -> RoutineStep("app",value).valid()
        else -> false
    }
}
@Serializable
data class RemoteLayout(val shortcuts: List<RemoteShortcut> = emptyList()) {
    fun valid() = shortcuts.size <= 8 && shortcuts.all { it.valid() } && shortcuts.map { it.kind to it.value }.distinct().size == shortcuts.size
    fun move(index: Int, direction: Int): RemoteLayout {
        if (direction !in listOf(-1,1) || index !in shortcuts.indices || index+direction !in shortcuts.indices) return this
        return copy(shortcuts=shortcuts.toMutableList().apply {val item=removeAt(index);add(index+direction,item)})
    }
}
