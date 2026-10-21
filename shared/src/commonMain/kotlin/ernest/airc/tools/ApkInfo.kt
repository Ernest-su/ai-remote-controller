package ernest.airc

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class ApkSignatureInfo(val scheme: String, val certificateSha256: List<String>, val certificateExtractionSupported: Boolean)
@Serializable
data class TargetApkInfo(
    val root: String, val path: String, @SerialName("package") val packageName: String,
    val versionCode: Long, val versionName: String? = null, val split: String? = null,
    val sizeBytes: Long, val sha256: String, val signatures: List<ApkSignatureInfo>, val signatureVerified: Boolean,
)
data class ApkInspectionState(val busy: Boolean = false, val info: TargetApkInfo? = null, val error: String? = null, val phase: String = "idle", val bytes: Long = 0, val total: Long? = null, val noticeId: String? = null)
