package ernest.airc

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class TargetMediaPlayers(val root: String, val path: String, val mimeType: String, val transport: String, val packages: List<String>, val sizeBytes: Long? = null)
@Serializable
data class TargetMediaOpen(val root: String, val path: String, val mimeType: String, val transport: String,
    val launched: Boolean, val playbackVerified: Boolean, val resourceId: String? = null, val output: String = "", @SerialName("package") val packageName: String? = null)
data class FileMediaState(val players: TargetMediaPlayers? = null, val selectedPackage: String? = null,
    val busy: Boolean = false, val error: String? = null, val message: String? = null, val resourceId: String? = null, val publicationId: String? = null, val localCopy: Boolean = false,
    val preparingCopy: Boolean = false, val bytes: Long = 0, val total: Long? = null, val retainedCopies: List<MediaPublication> = emptyList())
fun isTargetMedia(name: String): Boolean = name.substringAfterLast('.').lowercase() in
    listOf("png","jpg","jpeg","webp","gif","mp4","mkv","webm","mp3","m4a","wav","ogg","flac")

@Serializable
data class MediaPublication(val id:String,val root:String,val path:String,val sizeBytes:Long,val sha256:String)
@Serializable
data class MediaPublicationTask(val id:String,val phase:String,val bytesTransferred:Long,val sizeBytes:Long,val error:String?=null,val result:MediaPublication?=null,
    val direction:String="",val root:String="",val name:String="",val unit:String="")
