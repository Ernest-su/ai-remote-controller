package ernest.airc

import kotlinx.serialization.Serializable

val gamepadButtons = listOf("a","b","x","y","l1","r1","l2","r2","start","select","thumb_l","thumb_r","up","down","left","right")
@Serializable
data class GamepadPulse(val buttons: List<String> = emptyList(), val x: Double = 0.0, val y: Double = 0.0, val holdMs: Int = 100)
fun GamepadPulse.valid(): Boolean = buttons.size <= 4 && buttons.distinct().size == buttons.size && buttons.all { it in gamepadButtons } &&
    !("up" in buttons && "down" in buttons) && !("left" in buttons && "right" in buttons) &&
    x.isFinite() && y.isFinite() && x in -1.0..1.0 && y in -1.0..1.0 && holdMs in 10..2000 && (buttons.isNotEmpty() || x != 0.0 || y != 0.0)
data class GamepadControlState(val busy: Boolean = false, val message: String? = null, val error: String? = null)
