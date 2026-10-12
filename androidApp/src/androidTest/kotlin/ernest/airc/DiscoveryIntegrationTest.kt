package ernest.airc

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Android NSD resolver test; host runtime mDNS wire advertisement is tested separately. */
@RunWith(AndroidJUnit4::class)
class DiscoveryIntegrationTest {
    @Test fun resolvesIndependentRuntimeServiceTypeAndTxt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nsd = context.getSystemService(NsdManager::class.java)
        val id = UUID.randomUUID().toString()
        val registered = CompletableDeferred<Unit>()
        val found = CompletableDeferred<DiscoveredService>()
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { registered.complete(Unit) }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { registered.completeExceptionally(IllegalStateException("registration $code")) }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        val discovery = ServiceDiscovery(context, RUNTIME_SERVICE_TYPE, onFound = { if (it.attributes["id"] == id) found.complete(it) }, onError = { found.completeExceptionally(IllegalStateException(it)) })
        try {
            withContext(Dispatchers.Main) {
                discovery.start()
                nsd.registerService(NsdServiceInfo().apply {
                    serviceName = "airc-instrumentation-${id.take(8)}"
                    serviceType = RUNTIME_SERVICE_TYPE
                    port = 19234
                    setAttribute("id", id); setAttribute("protocol", "1"); setAttribute("name", "Integration device")
                }, NsdManager.PROTOCOL_DNS_SD, registration)
            }
            withTimeout(30_000) { registered.await() }
            val result = withTimeout(30_000) { found.await() }
            assertEquals(19234, result.port)
            assertEquals("1", result.attributes["protocol"])
            assertEquals("Integration device", result.attributes["name"])
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                discovery.close()
                runCatching { nsd.unregisterService(registration) }
            }
        }
    }
}
