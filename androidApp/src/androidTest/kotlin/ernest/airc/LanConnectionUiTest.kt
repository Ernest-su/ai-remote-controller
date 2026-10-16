package ernest.airc

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses only the controller's loopback address, never an external ADB target. */
class LanConnectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun startupConnectsLastSuccessfulDeviceAndUnexpectedLossReconnectsUntilExplicitDisconnect() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val history=context.getSharedPreferences("devices",0)
        val preferences=context.getSharedPreferences("runtime-release",0)
        val originalHistory=history.getString("history",null)
        val originalLast=preferences.getString("last-connected-device",null)
        val sockets=java.util.concurrent.CopyOnWriteArrayList<okhttp3.WebSocket>()
        val connections=java.util.concurrent.atomic.AtomicInteger()
        val server=okhttp3.mockwebserver.MockWebServer()
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest)=when(request.path) {
                "/status" -> {
                    connections.incrementAndGet()
                    okhttp3.mockwebserver.MockResponse().setBody(alignedRuntimeStatusFixture(
                        """{"version":"fixture","protocol":1,"deviceId":"automatic-connection-fixture","tools":[]}"""))
                }
                "/ws" -> okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(object:okhttp3.WebSocketListener() {
                    override fun onOpen(socket:okhttp3.WebSocket,response:okhttp3.Response) { sockets.add(socket) }
                    override fun onMessage(socket:okhttp3.WebSocket,text:String) {
                        val request=kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
                        socket.send("""{"id":${request["id"]},"protocol":1,"ok":true,"result":""}""")
                    }
                })
                else -> okhttp3.mockwebserver.MockResponse().setResponseCode(404)
            }
        }
        val owner=androidx.lifecycle.ViewModelStore()
        lateinit var model:RemoteViewModel
        try {
            server.start()
            val record=RememberedDevice("automatic-connection-fixture","fixture",ConnectionMethod.TCP,
                "127.0.0.1",runtimePort=server.port,lastConnectedAt=System.currentTimeMillis())
            DeviceStore(context).remember(record)
            preferences.edit().putString("last-connected-device",record.id).commit()
            compose.runOnUiThread {
                model=androidx.lifecycle.ViewModelProvider(owner,androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(
                    compose.activity.application))[RemoteViewModel::class.java]
            }
            compose.waitUntil(10000) { model.state.value.connected && !model.state.value.busy && sockets.size==1 }
            assertEquals(record.id,model.state.value.selected)
            assertTrue(sockets[0].close(1012,"fixture service restart"))
            compose.waitUntil(10000) { connections.get()==2 && sockets.size==2 && model.state.value.connected && !model.state.value.busy }
            compose.runOnUiThread { model.disconnect() }
            compose.waitUntil(10000) { !model.state.value.connected }
            Thread.sleep(3500)
            assertEquals(2,connections.get())
        } finally {
            compose.runOnUiThread { owner.clear() }
            server.shutdown()
            history.edit().apply { if(originalHistory==null)remove("history")else putString("history",originalHistory) }.commit()
            preferences.edit().apply { if(originalLast==null)remove("last-connected-device")else putString("last-connected-device",originalLast) }.commit()
        }
    }

    @Test fun failedLanDeploymentPrefillsFormAndPreservesEditedDraftAfterRecreation() {
        lateinit var model: RemoteViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java] }
        compose.onNodeWithTag("device-status").performClick()
        compose.runOnUiThread {
            model.discovered(DiscoveredService("unavailable fixture", "127.0.0.2", 1, emptyMap()))
        }
        compose.waitUntil(60_000) { !model.state.value.busy && model.state.value.status.contains("自动部署失败") }
        assertFalse(model.state.value.connected)
        compose.onAllNodesWithText(model.state.value.status).assertCountEquals(1)
        compose.onNodeWithText("已收到操作反馈").assertDoesNotExist()
        compose.onNodeWithText("目标设备 IP / 主机名").performScrollTo().assertTextContains("127.0.0.2")
        compose.onNodeWithText("ADB 端口").performScrollTo().assertTextContains("5555")
        compose.onNodeWithText("遥控服务端口").performScrollTo().assertTextContains("1")
        compose.onNodeWithText("目标设备 IP / 主机名").performScrollTo().performTextReplacement("192.0.2.25")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("目标设备 IP / 主机名").performScrollTo().assertTextContains("192.0.2.25")
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("remote-page").assertIsDisplayed()
    }

    @Test fun savedLanDeviceWithoutAdbPortAlsoAttemptsDefaultPort() {
        lateinit var model: RemoteViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java] }
        compose.onNodeWithTag("device-status").performClick()
        compose.runOnUiThread {
            model.reconnect(RememberedDevice("lan-fixture", "fixture", ConnectionMethod.TCP,
                host = "127.0.0.2", adbPort = null, runtimePort = 1, lastConnectedAt = 0))
        }
        compose.waitUntil(60_000) { !model.state.value.busy && model.state.value.status.contains("自动部署失败") }
        assertTrue(model.state.value.status.contains("127.0.0.2:5555"))
        compose.onNodeWithText("目标设备 IP / 主机名").performScrollTo().assertTextContains("127.0.0.2")
        compose.onNodeWithText("ADB 端口").performScrollTo().assertTextContains("5555")
    }
    @Test fun reachableButMismatchedServiceAttemptsUpgradeAndNeverEnablesRemoteActions() {
        val server = okhttp3.mockwebserver.MockWebServer()
        val aligned = kotlinx.serialization.json.Json.parseToJsonElement(alignedRuntimeStatusFixture(
            """{"version":"0.1.0","protocol":1,"deviceId":"mismatched-fixture","tools":[]}"""
        )) as kotlinx.serialization.json.JsonObject
        val mismatch = kotlinx.serialization.json.JsonObject(aligned + ("binarySha256" to kotlinx.serialization.json.JsonPrimitive("0".repeat(64))))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(mismatch.toString()))
        server.start(java.net.InetAddress.getByName("127.0.0.2"), 0)
        lateinit var model: RemoteViewModel
        try {
            compose.runOnUiThread { model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java] }
            compose.onNodeWithTag("device-status").performClick()
            compose.runOnUiThread { model.discovered(DiscoveredService("mismatch fixture", "127.0.0.2", server.port, emptyMap())) }
            compose.waitUntil(60_000) { !model.state.value.busy && model.state.value.status.contains("自动部署失败") }
            assertFalse(model.state.value.connected)
            assertEquals(1, server.requestCount)
            assertEquals("/status", server.takeRequest().path)
            compose.onNodeWithText("ADB 端口").performScrollTo().assertTextContains("5555")
        } finally { server.shutdown() }
    }

}
