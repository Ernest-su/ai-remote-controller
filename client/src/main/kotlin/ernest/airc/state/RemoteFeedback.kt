package ernest.airc

const val REMOTE_FEEDBACK_VISIBLE_MS = 5_000L

enum class FeedbackPhase(val label: String, val fallback: String) {
    THINKING("执行状态 · 思考中", "思考中…"),
    TOOL("执行状态 · 调用工具", "调用工具中…"),
    COMPLETE("执行结果", "执行完成"),
    ERROR("执行失败", "执行失败"),
}

data class RemoteFeedback(
    val phase: FeedbackPhase = FeedbackPhase.COMPLETE,
    val text: String = "",
    val revision: Long = 0L,
    val updatedAtNanos: Long = System.nanoTime(),
) {
    fun remainingVisibleMillis(nowNanos: Long = System.nanoTime()): Long =
        if (text.isBlank()) 0L else (REMOTE_FEEDBACK_VISIBLE_MS -
            ((nowNanos - updatedAtNanos) / 1_000_000L).coerceAtLeast(0L)).coerceAtLeast(0L)

    fun updated(phase: FeedbackPhase, text: String = ""): RemoteFeedback =
        copy(phase = phase, text = text.ifBlank { phase.fallback }, revision = revision + 1L, updatedAtNanos = System.nanoTime())
}

fun progressPhase(phase: String): FeedbackPhase? = when (phase) {
    "thinking", "planning" -> FeedbackPhase.THINKING
    "tool" -> FeedbackPhase.TOOL
    else -> null
}
