package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** One owned discovery instance bound to the interface used to reach this target. */
class DesktopDiscovery {
    suspend fun probe(host: String, port: Int): DiagnosticStage = runInterruptible(Dispatchers.IO) {
        val target = InetAddress.getAllByName(host).firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: return@runInterruptible skipped("未找到目标的可路由 IPv4 地址；ADB 回环端口不能用于组播发现。")
        val local = DatagramSocket().use { socket -> socket.connect(target, 5353); socket.localAddress }
        if (local.isAnyLocalAddress || local.isLoopbackAddress) return@runInterruptible skipped("无法确定目标局域网接口，请检查路由或手动连接。")
        JmDNS.create(local, "airc-diagnostic-${java.util.UUID.randomUUID().toString().take(8)}").use { dns ->
            val match = dns.list("_airc._tcp.local.", 3500).firstOrNull { matches(it, target, port) }
            if (match == null) skipped("未观察到匹配的 AIRC 公告；检查服务、同一局域网及组播设置，或手动连接。")
            else DiagnosticStage("mDNS", DiagnosticOutcome.PASSED, null, "已解析匹配公告：${match.name}，${target.hostAddress}:$port")
        }
    }
    private fun skipped(detail: String) = DiagnosticStage("mDNS", DiagnosticOutcome.SKIPPED, null, detail)
    companion object {
        fun matches(info: ServiceInfo, target: InetAddress, port: Int): Boolean =
            matches(info.type, info.port, info.inetAddresses.toList(), info.getPropertyString("protocol"), target, port)

        fun matches(type: String, advertisedPort: Int, addresses: List<InetAddress>, protocol: String?, target: InetAddress, port: Int): Boolean =
            type.equals("_airc._tcp.local.", ignoreCase = true) && advertisedPort == port &&
                addresses.any { it == target } && protocol == PROTOCOL_VERSION.toString()

        fun tcpAdbHost(serial: String?): String? {
            if (serial == null) return null
            if (serial.contains('/') || serial.contains('@')) return null
            val port = serial.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val endpoint = runCatching { okhttp3.HttpUrl.Builder().scheme("http").host("localhost").build().resolve("http://$serial/") }.getOrNull() ?: return null
            return endpoint.host.takeIf { endpoint.port == port }
        }
    }
}
