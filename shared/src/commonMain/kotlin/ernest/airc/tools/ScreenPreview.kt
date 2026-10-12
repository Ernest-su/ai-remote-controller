package ernest.airc

import kotlinx.serialization.Serializable

@Serializable
data class ScreenPreviewDescriptor(val id: String, val path: String, val mimeType: String,
    val format: String, val intervalMs: Int, val durationSeconds: Int, val claimWithinSeconds: Int,
    val maxFrameBytes: Int)

data class ScreenPreviewFrame(val sequence: Long, val elapsedMs: Long, val width: Int, val height: Int, val png: ByteArray)
data class ScreenPreviewState(val running: Boolean = false, val stopping: Boolean = false, val frame: ScreenPreviewFrame? = null,
    val message: String? = null, val error: String? = null)
