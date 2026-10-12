package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class GamepadController(private val scope: CoroutineScope, private val runtime: RuntimeConnection? = null,
    private val request: suspend (String,JsonObject)->String) {
    constructor(scope: CoroutineScope,runtime: RuntimeConnection): this(scope,runtime,{tool,args -> runtime.request("tool",tool,args)})
    private val mutable = MutableStateFlow(GamepadControlState())
    val state = mutable.asStateFlow()
    private var generation = 0L; private var job: Job? = null
    fun clear() { generation++; job?.cancel(); mutable.value = GamepadControlState() }
    fun pulse(input: GamepadPulse, available: Boolean): Boolean = input.valid() && send("gamepad.pulse",Json.encodeToJsonElement(GamepadPulse.serializer(),input).jsonObject,available)
    fun reset(available: Boolean) = send("gamepad.reset",buildJsonObject {},available)
    private fun send(tool: String,args: JsonObject,available: Boolean): Boolean {
        if(!available || state.value.busy) return false
        val token = generation; mutable.value = GamepadControlState(busy = true)
        job = scope.launch {
            try {
                val target = runtime?.transferTarget()
                val payload = if(runtime != null) runtime.requestAt(checkNotNull(target),tool,args) else request(tool,args)
                val result = Json.parseToJsonElement(payload).jsonObject
                if(runtime != null) runtime.checkTransferTarget(checkNotNull(target))
                check(result["submitted"]?.jsonPrimitive?.boolean == true && result["released"]?.jsonPrimitive?.boolean == true &&
                    result["sourceGamepad"]?.jsonPrimitive?.int == 1025 && result["sourceJoystick"]?.jsonPrimitive?.int == 16777232 &&
                    result["operation"]?.jsonPrimitive?.content == tool.substringAfter('.') && result["gameVerified"]?.jsonPrimitive?.boolean == false) { "手柄输入响应不匹配或释放未确认" }
                if(token == generation) mutable.update { it.copy(message = "输入、按钮释放及摇杆回中已提交；请核对目标实际行为。") }
            } catch(error: CancellationException) { throw error }
            catch(error: Exception) { if(token == generation) mutable.update { it.copy(error = error.message ?: "手柄输入失败") } }
            finally { if(token == generation) mutable.update { it.copy(busy = false) } }
        }
        return true
    }
}
