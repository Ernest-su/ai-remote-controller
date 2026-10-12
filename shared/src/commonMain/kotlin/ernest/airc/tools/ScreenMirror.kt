package ernest.airc
import kotlinx.serialization.Serializable
@Serializable
data class ScreenMirrorDescriptor(val id:String,val path:String,val mimeType:String,val format:String,val width:Int,val height:Int,val bitrate:Int,val durationSeconds:Int,val claimWithinSeconds:Int,val maxChunkBytes:Int,val maxStreamBytes:Long,val audio:Boolean)
