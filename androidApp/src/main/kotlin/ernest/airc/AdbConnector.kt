package ernest.airc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom

class PairingQr(providedName: String? = null, providedPassword: String? = null) {
    private val random = SecureRandom()
    private fun secret(length: Int): String = buildString {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
    val name = providedName ?: "studio-${secret(10)}"
    val password = providedPassword ?: secret(16)
    init {
        require((providedName == null) == (providedPassword == null)) { "二维码会话名称和密码必须同时提供" }
        require(this.name.matches(Regex("studio-[a-zA-Z0-9]{10}"))) { "无效的二维码会话名称" }
        require(this.password.matches(Regex("[a-zA-Z0-9]{16}"))) { "无效的二维码会话密码" }
    }
    fun bitmap(): Bitmap {
        val matrix = QRCodeWriter().encode("WIFI:T:ADB;S:$name;P:$password;;", BarcodeFormat.QR_CODE, 480, 480)
        return Bitmap.createBitmap(IntArray(480 * 480) { index ->
            if (matrix[index % 480, index / 480]) Color.BLACK else Color.WHITE
        }, 480, 480, Bitmap.Config.ARGB_8888)
    }
}

data class AdbSession(val client: AdbClient, val host: String, val port: Int, val guid: String?) : AutoCloseable {
    override fun close() = client.close()
}

class AdbConnector(private val context: Context) {
    private fun client() = DefaultAdbClient.factory(context).create()

    suspend fun tcp(host: String, port: Int): AdbSession = withContext(Dispatchers.IO) {
        val client = client()
        try {
            withTimeout(45_000) { client.connect(AdbEndpoint(host, port)) }
            AdbSession(client, host, port, null)
        } catch (e: Exception) { client.close(); throw e }
    }

    suspend fun pair(host: String, port: Int, code: String, connectionPort: Int? = null): AdbSession {
        require(code.matches(Regex("[0-9]{6}"))) { "请输入六位配对码" }
        require(connectionPort == null || connectionPort in 1..65535) { "连接端口必须在 1–65535 之间" }
        val guid = withContext(Dispatchers.IO) {
            val client = client()
            try { withTimeout(30_000) { client.pairWireless(AdbEndpoint(host, port), code) } }
            finally { client.close() }
        }
        return if (connectionPort != null) connectWireless(host, connectionPort, guid) else wireless(guid, host, null)
    }

    suspend fun qr(qr: PairingQr): AdbSession {
        val candidates = Channel<DiscoveredService>(Channel.UNLIMITED)
        val discovery = ServiceDiscovery(context, "_adb-tls-pairing._tcp.",
            onFound = { if (it.name == qr.name) candidates.trySend(it) },
            onError = { candidates.close(IllegalStateException(it)) })
        val pairing = try {
            withContext(Dispatchers.Main) { discovery.start() }
            withTimeout(120_000) { candidates.receive() }
        } finally { withContext(NonCancellable + Dispatchers.Main) { discovery.close() }; candidates.close() }
        val guid = withContext(Dispatchers.IO) {
            val client = client()
            try { withTimeout(30_000) { client.pairWireless(AdbEndpoint(pairing.host, pairing.port), qr.password) } }
            finally { client.close() }
        }
        return wireless(guid, pairing.host, null)
    }

    suspend fun wireless(guid: String, previousHost: String?, previousPort: Int?): AdbSession {
        val candidates = Channel<DiscoveredService>(Channel.UNLIMITED)
        val discovery = ServiceDiscovery(context, "_adb-tls-connect._tcp.",
            onFound = { if (it.name.contains(guid, ignoreCase = true)) candidates.trySend(it) })
        try {
            withContext(Dispatchers.Main) { discovery.start() }
            val found = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                while (true) {
                    val candidate = candidates.receive()
                    try { return@withTimeoutOrNull connectWireless(candidate.host, candidate.port, guid) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { /* Try another advertised endpoint. */ }
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            if (found != null) return found
            if (previousHost != null && previousPort != null) return connectWireless(previousHost, previousPort, guid)
            error("已配对，但未发现连接端口。请确认目标设备无线调试开启，可在设备历史中重连。")
        } finally { withContext(NonCancellable + Dispatchers.Main) { discovery.close() }; candidates.close() }
    }

    private suspend fun connectWireless(host: String, port: Int, guid: String): AdbSession = withContext(Dispatchers.IO) {
        val client = client()
        try {
            withTimeout(10_000) { client.connectWireless(AdbEndpoint(host, port)) }
            AdbSession(client, host, port, guid)
        } catch (e: Exception) { client.close(); throw e }
    }
}
