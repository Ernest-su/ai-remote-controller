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
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ConnectionRecoveryIntegrationTest {
    @get:Rule val historyIsolation: org.junit.rules.TestRule = DeviceHistoryIsolation()
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
        server.enqueue(MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"0.1.0","protocol":1,"deviceId":"recovery-runtime-fixture","tools":[]}""")))
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
            instrumentation.runOnMainSync { model.discovered(service, adbPort = 1) }
            waitUntil(10_000) { !model.state.value.busy && model.state.value.status.contains("自动部署失败") }
            assertFalse(model.state.value.connected)
            assertEquals(previous, devices.read().find { it.id == previous.id })
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            instrumentation.runOnMainSync { model.discovered(service, adbPort = 1) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            assertTrue(model.state.value.busy)
            instrumentation.runOnMainSync { model.cancelConnection() }
            waitUntil { !model.state.value.busy }
            assertEquals("已取消", model.state.value.status)
            assertFalse(model.state.value.connected)
            assertEquals(previous, devices.read().find { it.id == previous.id })
            instrumentation.runOnMainSync { model.discovered(service, adbPort = 1) }
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
    private fun readyServer(id: String) = MockWebServer().apply {
        dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                "/status" -> MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"fixture","protocol":1,"deviceId":"$id","tools":[]}"""))
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                        val request = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
                        webSocket.send("""{"id":${request["id"]},"protocol":1,"ok":true,"result":""}""")
                    }
                })
                else -> MockResponse().setResponseCode(404)
            }
        }
        start()
    }
    @Test fun explicitTargetReplacesAutomaticRecoveryAndLateReplyCannotOverwriteIt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val oldReply = java.util.concurrent.CountDownLatch(1)
        val old = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    oldReply.await(8, TimeUnit.SECONDS)
                    return MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"fixture","protocol":1,"deviceId":"automatic-old-fixture","tools":[]}"""))
                }
            }
            start()
        }
        val next = readyServer("manual-new-fixture")
        val record = RememberedDevice("automatic-old-fixture", "Old target", ConnectionMethod.TCP, "127.0.0.1", runtimePort = old.port, lastConnectedAt = 1)
        DeviceStore(context).remember(record)
        context.getSharedPreferences("runtime-release", 0).edit().putString("last-connected-device", record.id).commit()
        val owner = ViewModelStore()
        lateinit var model: RemoteViewModel
        try {
            instrumentation.runOnMainSync { model = RemoteViewModel(context.applicationContext as Application); owner.put("switch", model) }
            assertNotNull(withContext(Dispatchers.IO) { old.takeRequest(3, TimeUnit.SECONDS) })
            assertTrue(model.state.value.busy)
            instrumentation.runOnMainSync { model.discovered(DiscoveredService("New target", "127.0.0.1", next.port, emptyMap())) }
            withTimeout(5000) { while (model.state.value.busy || model.state.value.selected != "manual-new-fixture") delay(20) }
            assertTrue(model.state.value.connected)
            oldReply.countDown(); delay(300)
            assertEquals("manual-new-fixture", model.state.value.selected)
            assertFalse(model.state.value.busy)
            assertTrue(DeviceStore(context).read().any { it.id == record.id })
        } finally {
            oldReply.countDown()
            instrumentation.runOnMainSync { owner.clear() }
            old.shutdown(); next.shutdown()
        }
    }
    @Test fun stalledRecoveryDeadlineReleasesBusyAndAllowsAnotherTarget() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val stalled = MockWebServer().apply { enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); start() }
        val next = readyServer("after-timeout-fixture")
        val owner = ViewModelStore()
        lateinit var model: RemoteViewModel
        try {
            instrumentation.runOnMainSync {
                model = RemoteViewModel(context.applicationContext as Application, connectionTimeoutMillis = 2000)
                owner.put("deadline", model)
                model.discovered(DiscoveredService("Stalled target", "127.0.0.1", stalled.port, emptyMap()))
            }
            assertNotNull(withContext(Dispatchers.IO) { stalled.takeRequest(2, TimeUnit.SECONDS) })
            withTimeout(4000) { while (model.state.value.busy || !model.state.value.status.contains("连接超时")) delay(20) }
            assertFalse(model.state.value.connected)
            instrumentation.runOnMainSync { model.discovered(DiscoveredService("New target", "127.0.0.1", next.port, emptyMap())) }
            withTimeout(5000) { while (model.state.value.busy || model.state.value.selected != "after-timeout-fixture") delay(20) }
            assertTrue(model.state.value.connected)
        } finally {
            instrumentation.runOnMainSync { owner.clear() }
            stalled.shutdown(); next.shutdown()
        }
    }

}
