package ernest.airc
import kotlinx.serialization.Serializable
@Serializable
data class TargetSystemState(val runtimeUid:Int,val readOnly:Boolean,val available:Boolean,val source:String,
    val userId:Int?=null,val elapsedRealtimeMs:Long?=null,val interactive:Boolean?=null,
    val keyguardLocked:Boolean?=null,val deviceLocked:Boolean?=null,val deviceSecure:Boolean?=null,
    val errors:Map<String,String> = emptyMap(),val reason:String?=null)
data class SystemObservationState(val loading:Boolean=false,val observation:TargetSystemState?=null,val error:String?=null,val receivedAt:String?=null)
fun validSystemObservation(value:TargetSystemState):Boolean {
    val fields=listOf(value.interactive,value.keyguardLocked,value.deviceLocked,value.deviceSecure)
    return value.runtimeUid>=0 && value.readOnly && value.source=="android_framework" && (value.userId==null||value.userId==0) &&
        (value.elapsedRealtimeMs==null||value.elapsedRealtimeMs>=0) &&
        if(value.available)value.userId==0 && value.elapsedRealtimeMs?.let{it>=0}==true && fields.any{it!=null}
        else fields.all{it==null}
}
fun systemBooleanLabel(value:Boolean?):String=when(value){true->"是";false->"否";null->"未知"}
