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
    @Test fun stalledAdbShowsWaitingAndShortRetryReasonWithoutOpeningTheForm() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val http = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setResponseCode(503)
            }
            start()
        }
        val adb = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).apply { soTimeout = 3000 }
        val record = RememberedDevice("stalled-adb-fixture", "Fixture", ConnectionMethod.TCP,
            "127.0.0.1", adb.localPort, runtimePort = http.port, lastConnectedAt = 1)
        DeviceStore(context).remember(record)
        val owner = ViewModelStore()
        lateinit var model: RemoteViewModel
        var candidate: java.net.Socket? = null
        try {
            instrumentation.runOnMainSync {
                model = RemoteViewModel(context.applicationContext as Application, 10_000)
                owner.put("adb-waiting", model)
            }
            candidate = withContext(Dispatchers.IO) { adb.accept() }
            withTimeout(10_000) {
                while (model.state.value.status != "正在等待被控端 ADB 响应…") delay(20)
            }
            withTimeout(4000) {
                while (model.state.value.status != "被控端 ADB 未响应，3 秒后重试") delay(20)
            }
            assertFalse(model.state.value.busy)
            assertTrue(model.state.value.reconnecting)
            assertNull(model.state.value.manualConnectionTarget)
            assertNotEquals(FeedbackPhase.ERROR, model.state.value.feedback.phase)
            instrumentation.runOnMainSync { model.cancelConnection() }
            assertEquals("已取消", model.state.value.status)
        } finally {
            instrumentation.runOnMainSync { owner.clear() }
            candidate?.close(); adb.close(); http.shutdown()
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
            assertTrue(model.state.value.reconnecting)
            assertFalse(model.state.value.busy)
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

    @Test fun automaticTimeoutRetriesWithoutBusyFeedbackOrConnectionForm() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val context=instrumentation.targetContext
        val server=readyServer("silent-fixture")
        // The first status request is deliberately stalled; the following one uses the real dispatcher.
        val ready=server.dispatcher; var requests=0
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                if(request.path=="/status" && ++requests==1) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                return ready.dispatch(request)
            }
        }
        val record=RememberedDevice("silent-fixture","fixture",ConnectionMethod.TCP,"127.0.0.1",runtimePort=server.port,lastConnectedAt=1)
        DeviceStore(context).remember(record)
        val owner=ViewModelStore(); lateinit var model:RemoteViewModel
        try {
            instrumentation.runOnMainSync { model=RemoteViewModel(context.applicationContext as Application,500); owner.put("silent",model) }
            assertNotNull(withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)})
            delay(800)
            assertFalse(model.state.value.busy); assertTrue(model.state.value.reconnecting)
            assertNull(model.state.value.manualConnectionTarget)
            assertNotEquals(FeedbackPhase.ERROR,model.state.value.feedback.phase)
            withTimeout(6000){while(!model.state.value.connected)delay(20)}
            assertFalse(model.state.value.reconnecting);assertFalse(model.state.value.busy)
            assertEquals(record.id,model.state.value.selected)
        } finally {instrumentation.runOnMainSync{owner.clear()};server.shutdown()}
    }
    @Test fun recoveryPausesInBackgroundResumesAndHonorsExplicitCancellation() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val server=MockWebServer().apply {
            dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest)=MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)}
            start()
        }
        DeviceStore(context).remember(RememberedDevice("pause-fixture","fixture",ConnectionMethod.TCP,"127.0.0.1",runtimePort=server.port,lastConnectedAt=1))
        val owner=ViewModelStore();lateinit var model:RemoteViewModel
        try {
            instrumentation.runOnMainSync{model=RemoteViewModel(context.applicationContext as Application,2000);owner.put("pause",model)}
            assertNotNull(withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)})
            instrumentation.runOnMainSync{model.setConnectionForeground(false)}
            delay(2300);assertEquals(1,server.requestCount);assertFalse(model.state.value.busy)
            instrumentation.runOnMainSync{model.setConnectionForeground(true)}
            assertNotNull(withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)})
            instrumentation.runOnMainSync{model.cancelConnection()}
            delay(3500);assertEquals(2,server.requestCount)
            assertFalse(model.state.value.reconnecting);assertEquals("已取消",model.state.value.status)
        }finally{instrumentation.runOnMainSync{owner.clear()};server.shutdown()}
    }
    private class ManagementClient : ernest.ascrcpy.adb.AdbClient {
        override val state=kotlinx.coroutines.flow.MutableStateFlow<ernest.ascrcpy.adb.AdbConnectionState>(
            ernest.ascrcpy.adb.AdbConnectionState.Connected(ernest.ascrcpy.adb.AdbDevice(ernest.ascrcpy.adb.AdbEndpoint("127.0.0.1",5566),"fixture",emptyMap())))
        var failProbe=false; var statuses=0
        override suspend fun shell(command:String):ernest.ascrcpy.adb.AdbCommandResult {
            val result=when {
                command=="true" -> {if(failProbe)throw java.io.IOException("stale channel");""}
                command=="id -u" -> "2000"
                command.contains("service status") -> {statuses++;"{\"ready\":true,\"running\":true}"}
                else -> ""
            }
            return ernest.ascrcpy.adb.AdbCommandResult(result.toByteArray())
        }
        override suspend fun connect(endpoint:ernest.ascrcpy.adb.AdbEndpoint):ernest.ascrcpy.adb.AdbDevice=error("unused")
        override suspend fun disconnect(){close()}
        override suspend fun push(source:java.io.InputStream,remotePath:String,mode:Int)=error("unused")
        override suspend fun pull(remotePath:String,destination:java.io.OutputStream)=error("unused")
        override suspend fun open(service:String):ernest.ascrcpy.adb.AdbChannel=error("unused")
        override fun close(){state.value=ernest.ascrcpy.adb.AdbConnectionState.Disconnected}
    }
    @Test fun staleManagementChannelIsRecreatedOnDemandWithoutRestartingRemote() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val server=readyServer("management-fixture");val clients=mutableListOf<ManagementClient>()
        val owner=ViewModelStore();lateinit var model:RemoteViewModel
        try {
            instrumentation.runOnMainSync {
                model=RemoteViewModel(context.applicationContext as Application,adbConnectionFactory={
                    ManagementClient().also{clients+=it}.let{AdbSession(it,"127.0.0.1",5566,null)}
                });owner.put("management",model)
                model.discovered(DiscoveredService("fixture","127.0.0.1",server.port,emptyMap()),adbPort=5566)
            }
            withTimeout(5000){while(!model.state.value.connected||model.state.value.busy)delay(20)}
            val requests=server.requestCount;assertTrue(clients.isEmpty())
            instrumentation.runOnMainSync{model.serviceStatus()}
            withTimeout(3000){while(model.state.value.busy)delay(20)}
            assertEquals(1,clients.size);assertEquals(1,clients[0].statuses)
            instrumentation.runOnMainSync{clients[0].failProbe=true;model.serviceStatus()}
            withTimeout(3000){while(model.state.value.busy)delay(20)}
            assertEquals(2,clients.size);assertEquals(1,clients[0].statuses);assertEquals(1,clients[1].statuses)
            assertTrue(model.state.value.connected);assertEquals(requests,server.requestCount)
            withTimeout(1000){while(model.adbStatus.value!="已连接")delay(20)}
        } finally {instrumentation.runOnMainSync{owner.clear()};server.shutdown()}
    }

}
