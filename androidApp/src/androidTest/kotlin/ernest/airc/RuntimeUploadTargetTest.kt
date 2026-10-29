package ernest.airc

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import ernest.ascrcpy.adb.AdbChannel
import ernest.ascrcpy.adb.AdbClient
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

/** Explicit target, private directory, no service start/stop or changes to saved device records. */
class RuntimeUploadTargetTest {
    @Test fun compareLegacyCoalescedAndAutomaticCompression() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val host = requireNotNull(arguments.getString("uploadHost")) { "Explicit uploadHost required" }
        val activity = ActivityScenario.launch(ComponentActivity::class.java)
        val root = "/data/local/tmp/airc-upload-${UUID.randomUUID()}"
        val cache = File(context.cacheDir, root.substringAfterLast('/'))
        var session: AdbSession? = null
        fun report(text: String) { instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$text\n") }) }
        try {
            session = AdbConnector(context).tcp(host, 5555)
            val client = session.client
            val abi = client.shell("getprop ro.product.cpu.abi").text().trim()
            val asset = RuntimeInstaller(context).bundledRelease().selectAsset(listOf(abi))
            fun source() = context.assets.open("runtime/${asset.file}")
            check(client.shell("mkdir -m 700 $root && echo ready").text().trim() == "ready")
            val privilege = RuntimePrivilege.detect(arguments.getString("uploadRoot") == "true", root) { client.shell(it).text() }
            report("UPLOAD_TARGET $host $abi ${asset.size} ${privilege.description}")
            val uploader = RuntimeUploader(cache)
            val results = mutableListOf<JsonObject>()
            suspend fun run(name: String, action: suspend (String) -> Boolean): Boolean {
                val destination = "$root/$name"
                val start = SystemClock.elapsedRealtime()
                val compressed = withTimeout(600_000) { action(destination) }
                val elapsed = SystemClock.elapsedRealtime() - start
                if (compressed && privilege.useRoot) client.shell(privilege.command("chown 2000:2000 $destination && chmod 700 $destination"))
                val digest = MessageDigest.getInstance("SHA-256")
                client.pull(destination, DigestOutputStream(object : OutputStream() { override fun write(value: Int) {} ; override fun write(b: ByteArray, off: Int, len: Int) {} }, digest))
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                assertEquals("Read-back digest for $host/$name", asset.sha256, actual)
                val version = client.shell("$destination --version").text()
                assertTrue(version, version.lineSequence().any { it.trim() == BuildConfig.VERSION_NAME })
                val archive = cache.listFiles()?.firstOrNull { it.extension == "gz" }
                val result = buildJsonObject {
                    put("host", host); put("abi", abi); put("root", privilege.useRoot); put("method", name); put("rawBytes", asset.size)
                    put("compressed", compressed); put("compressedBytes", archive?.length() ?: 0); put("elapsedMillis", elapsed)
                    put("readBackVerified", true)
                }
                results += result; report("UPLOAD_RESULT $result")
                client.shell("rm -f $destination")
                return compressed
            }
            if (arguments.getString("uploadCompressionOnly") != "true") {
                run("legacy") { destination -> source().use { legacyPush(client, it, destination) }; false }
                run("coalesced") { destination -> source().use { client.push(it, destination, 0x1C0) }; false }
            }
            val cold = run("auto-cold") { destination ->
                uploader.upload(client, asset, ::source, destination, privilege) { report("UPLOAD_STAGE $it") }
            }
            if (cold) run("auto-warm") { destination ->
                uploader.upload(client, asset, ::source, destination, privilege) { report("UPLOAD_STAGE $it") }
            }
            // Force the no-decoder branch with a complete executable on the second target.
            if (host.endsWith(".59")) run("forced-raw") { destination ->
                RuntimeUploader(cache, emptyList()).upload(client, asset, ::source, destination,
                    privilege) { report("UPLOAD_STAGE $it") }
            }
            val remaining = client.shell("ls -A $root").text().trim()
            assertEquals("Compressed uploads must remove their temporary archives", "", remaining)
            File(context.filesDir, "upload-results-${host.substringAfterLast('.')}.json").writeText(JsonArray(results).toString())
        } finally {
            withContext(NonCancellable) { runCatching { withTimeout(10_000) { session?.client?.shell("rm -rf $root") } } }
            session?.close(); cache.deleteRecursively(); activity.close()
        }
    }
    private suspend fun legacyPush(client: AdbClient, source: InputStream, destination: String) {
        val channel = client.open("sync:")
        suspend fun frame(id: String, bytes: ByteArray, count: Boolean = true) {
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray())
                .putInt(if (count) bytes.size else ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int).array()
            channel.write(header)
            if (count) channel.write(bytes)
        }
        try {
            frame("SEND", "$destination,448".toByteArray())
            val buffer = ByteArray(65536)
            while (true) { val n = source.read(buffer); if (n < 0) break; frame("DATA", buffer.copyOf(n)) }
            frame("DONE", ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1700000000).array(), false)
            val status = ByteArray(8); var offset = 0
            while (offset < 8) { val n = channel.read(status, offset, 8 - offset); check(n > 0); offset += n }
            check(status.copyOfRange(0,4).toString(Charsets.US_ASCII) == "OKAY") { "Legacy sync push failed" }
        } finally { channel.close() }
    }
}
