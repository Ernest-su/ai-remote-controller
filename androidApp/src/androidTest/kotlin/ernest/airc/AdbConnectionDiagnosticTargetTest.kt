package ernest.airc

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import ernest.ascrcpy.adb.crypto.FileAdbKeyProvider
import ernest.ascrcpy.adb.transport.AdbTransport
import ernest.ascrcpy.adb.transport.AdbTransportFactory
import ernest.ascrcpy.adb.transport.TcpAdbTransport
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit, read-only ADB probe. Logs protocol headers only, never payloads or key material. */
class AdbConnectionDiagnosticTargetTest {
    @Test fun traceHandshakeToExplicitTarget() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val host = requireNotNull(args.getString("adbDiagnosticHost")) { "Explicit adbDiagnosticHost required" }
        val port = requireNotNull(args.getString("adbDiagnosticPort")).toInt()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = DefaultAdbClient(FileAdbKeyProvider.create(context), AdbTransportFactory { h, p ->
            val delegate = TcpAdbTransport(h, p)
            object : AdbTransport by delegate {
                override suspend fun connect() {
                    Log.i("AircAdbDiagnostic", "TCP connecting $h:$p")
                    delegate.connect()
                    Log.i("AircAdbDiagnostic", "TCP connected")
                }
                private fun header(direction: String, bytes: ByteArray, offset: Int, length: Int) {
                    if (length != 24) return
                    val data = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN)
                    val command = data.int
                    val arg0 = data.int
                    val arg1 = data.int
                    val size = data.int
                    data.int
                    if (data.int != command.inv()) return
                    val name = String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array(), Charsets.US_ASCII)
                    Log.i("AircAdbDiagnostic", "$direction $name arg0=$arg0 arg1=$arg1 payloadBytes=$size")
                }
                override suspend fun readExactly(buffer: ByteArray, offset: Int, length: Int) {
                    delegate.readExactly(buffer, offset, length)
                    header("RX", buffer, offset, length)
                }
                override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
                    header("TX", buffer, offset, length)
                    delegate.write(buffer, offset, length)
                }
            }
        })
        try {
            adbAttempt(15_000, client::close) { client.connect(AdbEndpoint(host, port)) }
            Log.i("AircAdbDiagnostic", "ADB authenticated")
            val output = adbAttempt(10_000, client::close) { client.shell("echo airc-read-only-probe").text().trim() }
            check(output == "airc-read-only-probe") { "Unexpected shell probe result" }
            Log.i("AircAdbDiagnostic", "Read-only shell probe passed")
        } catch (error: Exception) {
            Log.e("AircAdbDiagnostic", "Probe failed: ${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally { client.close() }
    }
}
