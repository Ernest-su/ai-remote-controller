package ernest.airc

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Real pairing/TLS test. Supply the target's temporary code/port as runner arguments. */
@RunWith(AndroidJUnit4::class)
class WirelessIntegrationTest {
    @Test fun pairsDeploysAndReconnectsWithPersistentIdentity() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val code = args.getString("wirelessCode")
        assumeNotNull(code)
        val host = args.getString("wirelessHost") ?: error("missing wirelessHost")
        val pairingPort = args.getString("wirelessPairingPort")!!.toInt()
        val connectionPort = args.getString("wirelessConnectionPort")?.toInt()
        val runtimePort = args.getString("wirelessRuntimePort")!!.toInt()
        val context = instrumentation.targetContext
        val session = AdbConnector(context).pair(host, pairingPort, code!!, connectionPort)
        val remote = RuntimeConnection()
        try {
            assertFalse(session.guid.isNullOrBlank())
            if (connectionPort != null) assertEquals(connectionPort, session.port)
            assertEquals("35", withContext(Dispatchers.IO) { session.client.shell("getprop ro.build.version.sdk").text().trim() })
            val installer = RuntimeInstaller(context)
            val release = installer.install(session.client, runtimePort = runtimePort) { }
            val actualPort = installer.start(session.client, runtimePort)
            val status = remote.connect(session.host, actualPort)
            assertEquals(release.version, status.version)
            assertTrue(remote.request("tool", "apps.list").contains("package:com.android.settings"))
            val record = RememberedDevice(status.deviceId, "Wireless integration target", ConnectionMethod.WIRELESS_CODE,
                session.host, session.port, session.guid, actualPort, System.currentTimeMillis())
            DeviceStore(context).remember(record)
            assertEquals(record, DeviceStore(context).read().find { it.id == status.deviceId })
            session.close()
            // New client, no pairing code: the phone's persistent RSA identity must work.
            val reconnect = DefaultAdbClient.factory(context).create()
            try {
                withContext(Dispatchers.IO) {
                    reconnect.connectWireless(AdbEndpoint(session.host, session.port))
                    assertEquals("35", reconnect.shell("getprop ro.build.version.sdk").text().trim())
                }
            } finally { reconnect.close() }
        } finally { remote.close(); session.close() }
    }
    @Test fun discoversThePairedTargetsCurrentConnectionPort() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val guid = InstrumentationRegistry.getArguments().getString("wirelessGuid")
        assumeNotNull(guid)
        val session = AdbConnector(instrumentation.targetContext).wireless(guid!!, null, null)
        try {
            InstrumentationRegistry.getArguments().getString("wirelessPreviousPort")?.toInt()?.let {
                assertNotEquals("discovery reused the stale connection port", it, session.port)
            }
            assertEquals("35", withContext(Dispatchers.IO) { session.client.shell("getprop ro.build.version.sdk").text().trim() })
        } finally { session.close() }
    }
    @Test fun resolvesIndependentRuntimeAndWirelessAdbAdvertisements() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val guid = args.getString("wirelessGuid")
        val id = args.getString("runtimeDeviceId")
        assumeNotNull(guid, id)
        val adb = CompletableDeferred<DiscoveredService>()
        val runtime = CompletableDeferred<DiscoveredService>()
        val adbDiscovery = ServiceDiscovery(instrumentation.targetContext, "_adb-tls-connect._tcp.",
            onFound = { if (it.name.contains(guid!!)) adb.complete(it) })
        val runtimeDiscovery = ServiceDiscovery(instrumentation.targetContext, RUNTIME_SERVICE_TYPE,
            onFound = { if (it.attributes["id"] == id) runtime.complete(it) })
        try {
            withContext(Dispatchers.Main) { adbDiscovery.start(); runtimeDiscovery.start() }
            val found = withTimeout(30_000) { runtime.await() }
            assertEquals("1", found.attributes["protocol"])
            val connection = RuntimeConnection()
            try { assertEquals(id, connection.connect(found.host, found.port).deviceId) }
            finally { connection.close() }
            withTimeout(30_000) { adb.await() }
        } finally { withContext(NonCancellable + Dispatchers.Main) { adbDiscovery.close(); runtimeDiscovery.close() } }
    }
    @Test fun renderedPairingQrDecodesToItsEphemeralCredentials() {
        val qr = PairingQr()
        val bitmap = qr.bitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luminance = com.google.zxing.RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val result = com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(luminance)))
        assertEquals("WIFI:T:ADB;S:${qr.name};P:${qr.password};;", result.text)
        val second = PairingQr()
        assertNotEquals(qr.name, second.name)
        assertNotEquals(qr.password, second.password)
        bitmap.recycle()
    }
    @Test fun qrDiscoveryPairsThroughTheTargetsRealAndroidService() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("qrName")
        val password = args.getString("qrPassword")
        assumeNotNull(name, password)
        val qr = PairingQr(name, password)
        val session = AdbConnector(instrumentation.targetContext).qr(qr)
        val runtime = RuntimeConnection()
        try {
            assertFalse(session.guid.isNullOrBlank())
            assertEquals("35", withContext(Dispatchers.IO) { session.client.shell("getprop ro.build.version.sdk").text().trim() })
            val status = runtime.connect(session.host, 18876)
            assertTrue(runtime.request("tool", "apps.list").contains("package:com.android.settings"))
            val record = RememberedDevice(status.deviceId, "QR integration target", ConnectionMethod.WIRELESS_QR,
                session.host, session.port, session.guid, 18876, System.currentTimeMillis())
            DeviceStore(instrumentation.targetContext).remember(record)
            assertEquals(record, DeviceStore(instrumentation.targetContext).read().find { it.id == status.deviceId })
        } finally { runtime.close(); session.close() }
    }
}
