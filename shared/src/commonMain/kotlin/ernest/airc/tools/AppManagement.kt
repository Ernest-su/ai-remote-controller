package ernest.airc
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
@Serializable
data class TargetAppInfo(@SerialName("package")val packageName:String,val userId:Int,val appId:Int,val versionCode:Long,val versionName:String?=null,val codePath:String,val lastUpdateTime:String,val system:Boolean)
@Serializable
data class AppUninstallProposal(val id:String,val kind:String,val app:TargetAppInfo,val approvalRequired:Boolean,val dataLoss:Boolean)
data class AppManagementState(val busy:Boolean=false,val info:TargetAppInfo?=null,val proposal:AppUninstallProposal?=null,val phase:String="idle",val message:String?=null,val error:String?=null)
