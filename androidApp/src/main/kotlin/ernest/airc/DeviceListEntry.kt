package ernest.airc

internal data class DeviceListEntry(
    val key: String,
    val history: List<RememberedDevice>,
    val service: DiscoveredService?,
    val adbDevice: DiscoveredAdbDevice?,
    val connectingName: String? = null,
) {
    val adbHost get() = adbDevice?.host
    val host get() = service?.host ?: adbHost ?: history.firstOrNull()?.host ?: key.takeUnless { it.startsWith("id:") }.orEmpty()
    val name get() = sequenceOf(history.firstOrNull()?.visibleName, connectingName, service?.attributes?.get("name"), service?.name,
        adbDevice?.name).firstOrNull { !it.isNullOrBlank() } ?: "Android 设备 · $host"
}

internal fun DeviceListEntry.isConnecting(state: RemoteUiState): Boolean {
    if (state.connected || !(state.busy || state.reconnecting)) return false
    val target = state.connectingDevice ?: return false
    fun normalized(host: String?) = host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]")
    return if (!target.host.isNullOrBlank()) normalized(host) == normalized(target.host) else history.any { it.id == target.id }
}

/** Merge discovery sources by address, preserving every historical identity for management. */
internal fun deviceListEntries(state: RemoteUiState): List<DeviceListEntry> {
    fun key(host: String) = host.trim().lowercase().removePrefix("[").removeSuffix("]")
    val history = state.history.groupBy { it.host?.takeIf(String::isNotBlank)?.let(::key) ?: "id:${it.id}" }
    val services = state.discovered.groupBy { key(it.host) }
    val adb = state.adbDevices.associateBy { key(it.host) }
    val connecting = state.connectingDevice?.takeIf { !state.connected && (state.busy || state.reconnecting) }
    val connectingKey = connecting?.host?.takeIf(String::isNotBlank)?.let(::key)
    return (history.keys + services.keys + adb.keys + listOfNotNull(connectingKey)).distinct().map { address ->
        DeviceListEntry(address, history[address].orEmpty().sortedByDescending { it.lastConnectedAt }, services[address]?.firstOrNull(), adb[address], connecting?.visibleName?.takeIf { address == connectingKey })
    }.sortedWith(compareByDescending<DeviceListEntry> { entry -> entry.history.any { it.id == state.selected } }
        .thenByDescending { it.service != null || it.adbHost != null }.thenBy { it.host })
}
