package ernest.airc

internal data class DeviceListEntry(
    val key: String,
    val history: List<RememberedDevice>,
    val service: DiscoveredService?,
    val adbDevice: DiscoveredAdbDevice?,
) {
    val adbHost get() = adbDevice?.host
    val host get() = service?.host ?: adbHost ?: history.firstOrNull()?.host.orEmpty()
    val name get() = sequenceOf(history.firstOrNull()?.visibleName, service?.attributes?.get("name"), service?.name,
        adbDevice?.name).firstOrNull { !it.isNullOrBlank() } ?: "Android 设备 · $host"
}

/** Merge discovery sources by address, preserving every historical identity for management. */
internal fun deviceListEntries(state: RemoteUiState): List<DeviceListEntry> {
    fun key(host: String) = host.trim().lowercase().removePrefix("[").removeSuffix("]")
    val history = state.history.groupBy { it.host?.takeIf(String::isNotBlank)?.let(::key) ?: "id:${it.id}" }
    val services = state.discovered.groupBy { key(it.host) }
    val adb = state.adbDevices.associateBy { key(it.host) }
    return (history.keys + services.keys + adb.keys).distinct().map { address ->
        DeviceListEntry(address, history[address].orEmpty().sortedByDescending { it.lastConnectedAt }, services[address]?.firstOrNull(), adb[address])
    }.sortedWith(compareByDescending<DeviceListEntry> { entry -> entry.history.any { it.id == state.selected } }
        .thenByDescending { it.service != null || it.adbHost != null }.thenBy { it.host })
}
