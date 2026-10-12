package ernest.airc

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a separate shell-accessible target; runner arguments select its route. */
@RunWith(AndroidJUnit4::class)
class RemoteIntegrationTest {
    @Test fun phoneDeploysAndControlsTarget() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("targetHost") ?: "10.0.2.2"
        val adbPort = args.getString("targetAdbPort")?.toInt() ?: 5557
        val port = args.getString("runtimePort")?.toInt() ?: 9876
        val session = AdbConnector(context).tcp(host, adbPort)
        val runtime = RuntimeConnection()
        val installer = RuntimeInstaller(context)
        try {
            val release = installer.install(session.client) { }
            assertEquals(1, release.protocol)
            val actual = installer.start(session.client, port)
            assertEquals(port, actual)
            val before = installer.status(session.client)
            assertTrue(before.contains("\"running\":true"))
            // Same asset should not restart or replace a healthy running process.
            installer.install(session.client) { }
            installer.start(session.client, port)
            assertEquals(before, installer.status(session.client))
            val status = runtime.connect(host, actual)
            assertEquals(release.version, status.version)
            val listed = runtime.request("tool", "apps.list")
            assertTrue(listed.contains("package:com.android.settings"))
            runtime.request("tool", "apps.open", buildJsonObject { put("package", "com.android.settings") })
            for (key in listOf("up", "down", "left", "right", "ok", "volume_up", "volume_down")) {
                runtime.request("tool", "remote.key", buildJsonObject { put("key", key) })
            }
            val store = DeviceStore(context)
            val device = RememberedDevice(status.deviceId, "Integration target", ConnectionMethod.TCP,
                host, adbPort, runtimePort = actual, lastConnectedAt = 123)
            store.remember(device)
            assertEquals(device, DeviceStore(context).read().find { it.id == device.id })
            args.getString("modelBaseUrl")?.let { baseUrl ->
                runtime.request("configure", arguments = buildJsonObject {
                    put("baseUrl", baseUrl); put("apiKey", "fixture-key"); put("model", "fixture-model")
                })
                assertEquals("fixture completed", runtime.request("text", text = "execute fixture shell"))
                val slow = async { runtime.request("text", text = "slow fixture shell") }
                delay(500)
                assertTrue("slow model request finished before direct control", slow.isActive)
                val direct = withTimeout(2_000) {
                    runtime.request("tool", "device.shell", buildJsonObject { put("command", "printf controls-inflight") })
                }
                assertEquals("controls-inflight", direct)
                assertTrue("direct control waited for the model", slow.isActive)
                assertEquals("fixture completed", slow.await())
            }
            runtime.disconnect()
            assertTrue(installer.stop(session.client).contains("\"running\":false"))
            installer.start(session.client, port)
            val restarted = runtime.connect(host, port)
            assertEquals(status.deviceId, restarted.deviceId)
        } finally { runtime.close(); session.close() }
    }
}
