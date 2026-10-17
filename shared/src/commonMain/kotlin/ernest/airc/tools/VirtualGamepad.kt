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

enum class GamepadType(val label: String, val shortLabel: String) {
    DUAL_STICK("双摇杆手柄", "双摇杆"),
    NES("NES 经典手柄", "NES"),
}

/** Synthetic input uses digital DPAD keys; uinput uses the snapshot's hat axes. */
fun VirtualGamepadInput.toPulse(): GamepadPulse = GamepadPulse(
    buttons = buttons + listOfNotNull(
        "left".takeIf { hatX < 0 }, "right".takeIf { hatX > 0 },
        "up".takeIf { hatY < 0 }, "down".takeIf { hatY > 0 }),
    x = leftX, y = leftY, rightX = rightX, rightY = rightY,
)

data class VirtualGamepadState(
    val type: GamepadType = GamepadType.DUAL_STICK,
    val switchingType: Boolean = false,
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
