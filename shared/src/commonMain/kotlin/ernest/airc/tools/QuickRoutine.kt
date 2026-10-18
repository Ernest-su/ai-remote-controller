package ernest.airc

import kotlinx.serialization.Serializable

val routineKeys = listOf("up", "down", "left", "right", "ok", "back", "home", "menu", "play_pause", "media_previous", "media_next", "volume_down", "volume_up", "mute")

@Serializable
data class RoutineStep(val type: String, val value: String) {
    fun valid(): Boolean = when (type) {
        "key" -> value in routineKeys
        "app" -> value.length <= 255 && value.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+"))
        "wait" -> value.toLongOrNull() in 100L..10000L
        else -> false
    }
    fun description(): String = when (type) { "app" -> "打开 $value"; "wait" -> "等待 $value 毫秒"; else -> "按键 ${routineKeyLabel(value)}" }
}

@Serializable
data class QuickRoutine(val id: String, val name: String, val steps: List<RoutineStep>) {
    fun valid() = id.isNotBlank() && id.length <= 64 && name.isNotBlank() && name.length <= 40 &&
        steps.size in 1..24 && steps.all { it.valid() } && steps.filter { it.type == "wait" }.sumOf { it.value.toLong() } <= 60000
}

data class RoutineRunState(val busy: Boolean = false, val index: Int = 0, val total: Int = 0, val message: String? = null, val error: String? = null)

fun routineKeyLabel(key: String): String = when (key) {
    "up" -> "向上"; "down" -> "向下"; "left" -> "向左"; "right" -> "向右"; "ok" -> "确认"
    "back" -> "返回"; "home" -> "主页"; "menu" -> "菜单"; "play_pause" -> "播放/暂停"
    "media_previous" -> "上一曲"; "media_next" -> "下一曲"; "volume_down" -> "音量 −"; "volume_up" -> "音量 +"; "mute" -> "静音"
    else -> key
}
