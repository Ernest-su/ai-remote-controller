package ernest.airc

import kotlinx.serialization.Serializable

@Serializable
data class BinaryArtifact(
    val id: String,
    val path: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val expiresInSeconds: Int,
)
