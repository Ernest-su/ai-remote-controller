package ernest.airc

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Real HTTPS bytes and pinned release signature, followed by real target ADB deployment. */
@RunWith(AndroidJUnit4::class)
class SignedOnlineInstallIntegrationTest {
    @Test fun downloadsSignedReleaseInstallsItAndRejectsTampering() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("signedTargetHost")
        assumeNotNull(host)
        val adbPort = args.getString("signedTargetAdbPort")!!.toInt()
        val runtimePort = args.getString("signedRuntimePort")!!.toInt()
        val context = instrumentation.targetContext
        val fixtureAssets = instrumentation.context.assets
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        val downloads = AtomicInteger(0)
        var tampered = false
        val server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val name = request.path!!.substringAfterLast('/')
                    val bytes = when (name) {
                        "manifest.json" -> fixtureAssets.open(name).use { it.readBytes() }.let { if (tampered) it + byteArrayOf(32) else it }
                        "manifest.json.sig" -> fixtureAssets.open(name).use { it.readBytes() }
                        else -> {
                            downloads.incrementAndGet()
                            context.assets.open("runtime/$name").use { it.readBytes() }
                        }
                    }
                    return MockResponse().setBody(Buffer().write(bytes))
                }
            }
            start(19990)
        }
        val adb = DefaultAdbClient.factory(context).create()
        val remote = RuntimeConnection()
        val installer = RuntimeInstaller(context) { cache -> RuntimeReleaseRepository(cache, client) }
        try {
            withContext(Dispatchers.IO) { adb.connectWireless(AdbEndpoint(host!!, adbPort)) }
            // Empty only this application's download cache, so HTTPS asset transfer is exercised.
            withContext(Dispatchers.IO) {
                java.io.File(context.cacheDir, "runtime-releases").listFiles()?.forEach { check(it.delete()) }
            }
            val url = "https://localhost:19990/release/manifest.json"
            val release = installer.install(adb, url, runtimePort) { }
            assertEquals(1, downloads.get())
            val actual = installer.start(adb, runtimePort)
            val status = remote.connect(host!!, actual)
            assertEquals(release.version, status.version)
            assertTrue(remote.request("tool", "apps.list").contains("package:com.android.settings"))
            val before = installer.status(adb)
            installer.install(adb, url, runtimePort) { }
            assertEquals(1, downloads.get())
            assertEquals(before, installer.status(adb))
            tampered = true
            try { installer.install(adb, url, runtimePort) { }; fail("tampered manifest was accepted") }
            catch (error: IllegalStateException) { assertTrue(error.message!!.contains("签名")) }
            assertEquals(before, installer.status(adb))
            assertEquals("true", Json.parseToJsonElement(before).jsonObject.getValue("ready").toString())
        } finally { remote.close(); adb.close(); server.shutdown() }
    }
}
