package ernest.airc

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Target-only fixture: enables the same Android pairing service used by its QR scanner. */
@RunWith(AndroidJUnit4::class)
class QrPairingTargetFixture {
    @Test fun servesTheScannedQrCredentials() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("qrName")
        val password = args.getString("qrPassword")
        assumeNotNull(name, password)
        val stub = Class.forName("android.debug.IAdbManager\$Stub")
        fun command(method: String, arguments: String = "") {
            val transaction = stub.getDeclaredField("TRANSACTION_$method").apply { isAccessible = true }.getInt(null)
            val descriptor = instrumentation.uiAutomation.executeShellCommand("service call adb $transaction $arguments")
            val result = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            check(result.contains("00000000") && !result.contains("Exception")) { "target pairing service call failed: $result" }
        }
        try {
            PairingQr(name, password) // Validate shell arguments against the QR session format.
            command("enablePairingByQrCode", "s16 $name s16 $password")
            instrumentation.sendStatus(2, Bundle().apply { putString("fixture", "qr-pairing-service-started") })
            delay(30_000)
        } finally {
            command("disablePairing")
        }
    }
}
