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

data class RoutineRunState(val busy: Boolean = false, val index: Int = 0, val total: Int = 0, val message: String? = null, val error: String? = null,
    val routineId: String? = null, val paused: Boolean = false, val pauseRequested: Boolean = false)

fun routineKeyLabel(key: String): String = when (key) {
    "up" -> "向上"; "down" -> "向下"; "left" -> "向左"; "right" -> "向右"; "ok" -> "确认"
    "back" -> "返回"; "home" -> "主页"; "menu" -> "菜单"; "play_pause" -> "播放/暂停"
    "media_previous" -> "上一曲"; "media_next" -> "下一曲"; "volume_down" -> "音量 −"; "volume_up" -> "音量 +"; "mute" -> "静音"
    else -> key
}

fun parseRoutineSeconds(text: String): Long? {
    val parts = text.trim().split('.')
    if (parts.size !in 1..2 || parts[0].isEmpty() || parts[0].length > 2 || parts[0].any { it !in '0'..'9' }) return null
    val fraction = parts.getOrNull(1).orEmpty()
    if (parts.size == 2 && (fraction.isEmpty() || fraction.length > 3 || fraction.any { it !in '0'..'9' })) return null
    val millis = parts[0].toLong() * 1000 + fraction.padEnd(3, '0').toLong()
    return millis.takeIf { it in 100..10000 }
}

fun routineSeconds(milliseconds: Long): String {
    val fraction = (milliseconds % 1000).toString().padStart(3, '0').trimEnd('0')
    return (milliseconds / 1000).toString() + if (fraction.isEmpty()) "" else ".$fraction"
}

fun moveRoutineStep(steps: List<RoutineStep>, index: Int, direction: Int): List<RoutineStep> {
    val next = index + direction
    if (index !in steps.indices || next !in steps.indices || direction !in listOf(-1, 1)) return steps
    return steps.toMutableList().apply { this[index] = steps[next]; this[next] = steps[index] }
}

@Serializable
enum class RoutineStatus { RUNNING, PAUSED, SUCCEEDED, STOPPED, FAILED }
@Serializable
enum class RoutineStepStatus { PENDING, RUNNING, SUCCEEDED, FAILED, INTERRUPTED }
@Serializable
data class RoutineStepExecution(val status: RoutineStepStatus = RoutineStepStatus.PENDING, val startedAt: Long? = null,
    val finishedAt: Long? = null, val output: String? = null, val error: String? = null)
@Serializable
data class RoutineExecution(val routine: QuickRoutine, val deviceId: String? = null, val targetName: String? = null,
    val startedAt: Long, val finishedAt: Long? = null, val status: RoutineStatus = RoutineStatus.RUNNING,
    val steps: List<RoutineStepExecution> = routine.steps.map { RoutineStepExecution() }, val error: String? = null) {
    fun afterRestart(): RoutineExecution = if (status in listOf(RoutineStatus.RUNNING, RoutineStatus.PAUSED)) copy(
        status = RoutineStatus.STOPPED, error = "应用已重启，执行未继续；请核对电视上已提交的动作。",
        steps = steps.map { if (it.status == RoutineStepStatus.RUNNING) it.copy(status = RoutineStepStatus.INTERRUPTED) else it }) else this
}
