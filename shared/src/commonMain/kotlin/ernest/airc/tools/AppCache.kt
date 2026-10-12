package ernest.airc
import kotlinx.serialization.Serializable
@Serializable
data class AppCacheProposal(val id:String,val kind:String,val app:TargetAppInfo,val approvalRequired:Boolean,val cacheOnly:Boolean,val mode:String)
data class AppCacheState(val busy:Boolean=false,val proposal:AppCacheProposal?=null,val phase:String="idle",val message:String?=null,val error:String?=null)
