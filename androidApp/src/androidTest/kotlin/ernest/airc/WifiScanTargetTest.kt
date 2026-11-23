package ernest.airc

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit physical Wi-Fi target only; no authorization, deployment or remote input. */
@RunWith(AndroidJUnit4::class)
class WifiScanTargetTest {
    @get:Rule val history: org.junit.rules.TestRule = DeviceHistoryIsolation()

    @Test fun wifiReconnectRefreshesSubnetAndFindsAdvertisedAdbPort() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit opt-in required for Wi-Fi toggle", args.getString("toggleWifi") == "true")
        val host = requireNotNull(args.getString("expectedAdbHost"))
        val prefix = requireNotNull(args.getString("expectedWifiPrefix"))
        val port = requireNotNull(args.getString("expectedAdbPort")).toInt()
        val context = instrumentation.targetContext
        assertNotNull("Wi-Fi must initially be connected", wifiScanRange(context))
        fun wifi(enabled: Boolean) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("svc wifi ${if (enabled) "enable" else "disable"}")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        val store = ViewModelStore()
        try {
            val model = withContext(Dispatchers.Main) {
                val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
                ViewModelProvider(store, factory)[RemoteViewModel::class.java].also { it.disconnect(); it.scanAdbDevices() }
            }
            withTimeout(10_000) { model.state.first { it.adbScanStatus.contains(prefix) } }
            wifi(false)
            val offline = withTimeout(20_000) { model.state.first { it.adbScanStatus.contains("请连接 Wi-Fi") } }
            assertTrue(offline.adbDevices.isEmpty())
            wifi(true)
            // History is isolated and only 5555 is swept: any other port must come from mDNS.
            val found = withTimeout(60_000) {
                model.state.first { state -> state.adbScanStatus.contains(prefix) && state.adbDevices.any { it.host == host && it.port == port } }
            }
            val box = found.adbDevices.single { it.host == host }
            assertEquals(port, box.port)
            assertEquals("rk356x_box", box.name)
            val row = deviceListEntries(found).single { it.host == host }
            assertEquals(port, row.adbPort)
            assertFalse(found.connected)
        } finally {
            wifi(true)
            withContext(NonCancellable + Dispatchers.Main) { store.clear() }
        }
    }
}
