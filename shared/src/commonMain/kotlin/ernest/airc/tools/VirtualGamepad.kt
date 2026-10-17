package ernest.airc

import kotlinx.serialization.Serializable

@Serializable
data class VirtualGamepadInput(
    val buttons: List<String> = emptyList(),
    val leftX: Double = 0.0, val leftY: Double = 0.0,
    val rightX: Double = 0.0, val rightY: Double = 0.0,
    val hatX: Int = 0, val hatY: Int = 0,
) {
    fun valid(): Boolean = buttons.size <= 12 && buttons.distinct().size == buttons.size &&
        buttons.all { it in virtualGamepadButtons } &&
        listOf(leftX, leftY, rightX, rightY).all { it.isFinite() && it in -1.0..1.0 } &&
        hatX in -1..1 && hatY in -1..1
}
val virtualGamepadButtons = listOf("a", "b", "x", "y", "l1", "r1", "l2", "r2", "select", "start", "thumb_l", "thumb_r")

data class VirtualGamepadState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val stopping: Boolean = false,
    val reconnecting: Boolean = false,
    val input: VirtualGamepadInput = VirtualGamepadInput(),
    val sequence: Long = 0,
    val deviceName: String? = null,
    val mode: String? = null,
    val error: String? = null,
)
