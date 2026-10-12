package ernest.airc

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

internal data class WifiScanRange(val hosts: List<String>, val label: String)
internal fun wifiScanRange(context: Context): WifiScanRange? {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager.allNetworks.firstOrNull {
        manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } ?: return null
    val link = manager.getLinkProperties(network)?.linkAddresses?.firstOrNull {
        it.address is Inet4Address && !it.address.isLoopbackAddress
    } ?: return null
    val ip = link.address.hostAddress ?: return null
    return WifiScanRange(AdbSubnetProbe.hosts(ip, link.prefixLength),
        if (link.prefixLength < 22) "$ip/24（大网段仅扫描当前 /24）" else "$ip/${link.prefixLength}")
}
