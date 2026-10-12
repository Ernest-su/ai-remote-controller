package ernest.airc

fun validatedDeviceName(value: String): String = value.trim().also {
    require(it.isNotEmpty() && it.length <= 64 && it.none { c -> c.isISOControl() }) {
        "设备显示名须为 1–64 个字符，不能包含控制字符"
    }
}

val RememberedDevice.visibleName: String get() = displayName ?: name

fun renameDevice(history: List<RememberedDevice>, id: String, name: String): List<RememberedDevice> {
    val normalized = validatedDeviceName(name)
    require(history.any { it.id == id }) { "设备记录已不存在" }
    return history.map { if (it.id == id) it.copy(displayName = normalized) else it }
}
