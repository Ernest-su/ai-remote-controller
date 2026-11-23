package ernest.airc

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address

internal data class WifiScanRange(val hosts: List<String>, val label: String, val network: Network)
internal fun wifiScanRange(context: Context, excluded: Network? = null): WifiScanRange? {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    return manager.allNetworks.asSequence().filter { it != excluded }.mapNotNull { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
        // VPNs can inherit WIFI from their underlying network, but have a different subnet.
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
        val link = manager.getLinkProperties(network)?.linkAddresses?.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress
        } ?: return@mapNotNull null
        val ip = link.address.hostAddress ?: return@mapNotNull null
        WifiScanRange(AdbSubnetProbe.hosts(ip, link.prefixLength),
            if (link.prefixLength < 22) "$ip/24（大网段仅扫描当前 /24）" else "$ip/${link.prefixLength}", network)
    }.firstOrNull()
}

/** Observe the physical Wi-Fi even when VPN or cellular is the default network. */
internal fun wifiScanRanges(context: Context) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(wifiScanRange(context)) }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { trySend(wifiScanRange(context)) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(wifiScanRange(context)) }
        override fun onLost(network: Network) { trySend(wifiScanRange(context, excluded = network)) }
    }
    manager.registerNetworkCallback(NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback, Handler(Looper.getMainLooper()))
    trySend(wifiScanRange(context))
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
