package ernest.airc

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ConnectionRecoveryIntegrationTest {
    @Test fun cancelsBlockedConnectionAndReconnectsWithoutLosingHistory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val devices = DeviceStore(context)
        val previous = RememberedDevice("recovery-history-fixture", "remembered", ConnectionMethod.TCP,
            "127.0.0.1", 5555, lastConnectedAt = 1)
        devices.remember(previous)
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody("""{"version":"0.1.0","protocol":1,"deviceId":"recovery-runtime-fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.start()
        val store = ViewModelStore()
        lateinit var model: RemoteViewModel
        instrumentation.runOnMainSync {
            model = RemoteViewModel(context.applicationContext as Application)
            store.put("connection-recovery", model)
        }
        suspend fun waitUntil(timeout: Long = 2_000, condition: () -> Boolean) = withTimeout(timeout) {
            while (!condition()) delay(20)
        }
        val service = DiscoveredService("recovery fixture", "127.0.0.1", server.port, emptyMap())
        try {
            instrumentation.runOnMainSync { model.discovered(service) }
            waitUntil { !model.state.value.busy && model.state.value.status.contains("503") }
            assertFalse(model.state.value.connected)
            assertEquals(previous, devices.read().find { it.id == previous.id })
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            instrumentation.runOnMainSync { model.discovered(service) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            assertTrue(model.state.value.busy)
            instrumentation.runOnMainSync { model.cancelConnection() }
            waitUntil { !model.state.value.busy }
            assertEquals("已取消", model.state.value.status)
            assertFalse(model.state.value.connected)
            assertEquals(previous, devices.read().find { it.id == previous.id })
            instrumentation.runOnMainSync { model.discovered(service) }
            waitUntil(10_000) { !model.state.value.busy && model.state.value.connected }
            assertEquals("recovery-runtime-fixture", model.state.value.selected)
            assertEquals(previous, devices.read().find { it.id == previous.id })
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            server.shutdown()
            devices.remove(previous.id)
            devices.remove("recovery-runtime-fixture")
        }
    }
}
