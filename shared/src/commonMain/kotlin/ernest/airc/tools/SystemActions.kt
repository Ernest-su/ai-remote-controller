package ernest.airc
import kotlinx.serialization.Serializable
@Serializable
data class SystemActionProposal(val id:String,val kind:String,val action:String,val runtimeUid:Int,val approvalRequired:Boolean,val connectionMayClose:Boolean,val expiresInSeconds:Int)
data class SystemActionState(val busy:Boolean=false,val proposal:SystemActionProposal?=null,val message:String?=null,val error:String?=null,val requiresVerification:Boolean=false)
fun validSystemProposal(p:SystemActionProposal):Boolean = p.id.matches(Regex("[a-f0-9-]{36}")) && p.kind=="system_action" && p.action in listOf("lock","reboot","shutdown") && p.runtimeUid==0 && p.approvalRequired && p.expiresInSeconds in 1..120 && p.connectionMayClose==(p.action!="lock")
fun systemActionLabel(action:String):String = when(action){"lock"->"锁屏";"reboot"->"重启";"shutdown"->"关机";else->"未知操作"}
