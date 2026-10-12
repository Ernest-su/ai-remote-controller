package ernest.airc

/** Reported runtime identity and one connection generation; not an authentication claim. */
data class RuntimeSession(val deviceId:String,val host:String,val port:Int,val generation:Int)
