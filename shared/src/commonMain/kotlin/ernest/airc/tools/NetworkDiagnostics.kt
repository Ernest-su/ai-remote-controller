package ernest.airc

enum class DiagnosticOutcome { PASSED, FAILED, SKIPPED }
data class DiagnosticStage(val name: String, val outcome: DiagnosticOutcome, val elapsedMs: Long?, val detail: String)
data class DiagnosticState(val running: Boolean = false, val stages: List<DiagnosticStage> = emptyList())
