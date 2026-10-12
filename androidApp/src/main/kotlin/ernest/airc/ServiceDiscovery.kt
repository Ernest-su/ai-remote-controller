package ernest.airc

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

data class DiscoveredService(val name: String, val host: String, val port: Int, val attributes: Map<String, String>)

/** DNS-SD discovery is scoped to a screen/session and releases its multicast lock. */
class ServiceDiscovery(
    context: Context,
    private val type: String,
    private val onFound: (DiscoveredService) -> Unit,
    private val onLost: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
) : AutoCloseable {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lock = context.applicationContext.getSystemService(WifiManager::class.java)
        .createMulticastLock("airc-$type").apply { setReferenceCounted(false) }
    private var active = false
    private var generation = 0
    private var resolving = false
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val seen = mutableSetOf<String>()
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            main.post { if (active) onError("发现失败 ($errorCode)") }
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceFound(info: NsdServiceInfo) {
            main.post { if (active && seen.add(info.serviceName)) { pending.add(info); resolveNext() } }
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            main.post { seen.remove(info.serviceName); if (active) onLost(info.serviceName) }
        }
    }
    fun start() {
        if (active) return
        active = true
        generation++
        lock.acquire()
        try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (e: Exception) { close(); onError(e.message ?: "无法发现服务") }
    }
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (!active || resolving || pending.isEmpty()) return
        val info = pending.removeFirst()
        val attempt = generation
        resolving = true
        fun finished(error: String? = null) {
            if (attempt != generation) return
            resolving = false
            if (error != null) seen.remove(info.serviceName)
            resolveNext()
        }
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post { finished("解析失败 $errorCode") }
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    main.post {
                        if (active && attempt == generation && serviceInfo.host != null && serviceInfo.port in 1..65535) {
                            onFound(DiscoveredService(serviceInfo.serviceName, serviceInfo.host.hostAddress!!,
                                serviceInfo.port, serviceInfo.attributes.mapValues { it.value.toString(Charsets.UTF_8) }))
                        }
                        finished()
                    }
                }
            })
        } catch (e: Exception) { finished(e.message ?: "解析失败") }
    }
    override fun close() {
        if (!active) return
        active = false
        generation++
        pending.clear(); seen.clear(); resolving = false
        runCatching { nsd.stopServiceDiscovery(listener) }
        if (lock.isHeld) lock.release()
    }
}
