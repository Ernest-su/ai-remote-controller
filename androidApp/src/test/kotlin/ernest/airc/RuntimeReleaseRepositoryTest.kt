package ernest.airc

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.security.KeyPairGenerator
import java.security.Signature
import okio.Buffer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.*
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit

class RuntimeReleaseRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val signingKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun repository(cache: java.io.File, client: OkHttpClient) = RuntimeReleaseRepository(cache, client, ReleaseSignatureVerifier(signingKey.public))
    private fun signed(server: MockWebServer, release: RuntimeRelease) {
        val payload = Json.encodeToString(release).toByteArray()
        val signer = Signature.getInstance("SHA256withRSA").apply { initSign(signingKey.private); update(payload) }
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        server.enqueue(MockResponse().setBody(Buffer().write(signer.sign())))
    }
    private val bytes = "native-runtime-fixture".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun server(): Pair<MockWebServer, OkHttpClient> {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        return MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() } to
            OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).followRedirects(true).followSslRedirects(false).build()
    }
    @Test fun repositoryAddressResolvesToLatestSignedManifest() {
        assertEquals("https://github.com/Ernest-su/ai-remote-controller/releases/latest/download/manifest.json",
            RuntimeReleaseRepository.githubManifest().toString())
        assertEquals("https://github.com/owner/repo/releases/latest/download/manifest.json",
            RuntimeReleaseRepository.githubManifest("https://github.com/owner/repo.git/").toString())
        for (url in listOf("https://example.com/owner/repo", "https://github.com/owner", "https://github.com/owner/repo/releases", "https://github.com/owner/repo?token=x")) {
            assertThrows(IllegalArgumentException::class.java) { RuntimeReleaseRepository.githubManifest(url) }
        }
    }

    @Test fun verifiesCachesAndRepairsCorruptedDownloads() = runBlocking<Unit> {
        val (server, client) = server()
        try {
            val repo = repository(temporary.newFolder(), client)
            val asset = RuntimeAsset("x86_64", "runtime", hash, bytes.size.toLong(), server.url("/binary").toString())
            server.enqueue(MockResponse().setBody(String(bytes)))
            val file = repo.asset(asset) { }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(file, repo.asset(asset) { })
            assertEquals(1, server.requestCount)
            file.writeText("corrupted")
            server.enqueue(MockResponse().setBody(String(bytes)))
            assertArrayEquals(bytes, repo.asset(asset) { }.readBytes())
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun rejectsWrongBytesAndNeverCachesPartialContent() = runBlocking<Unit> {
        val (server, client) = server()
        try {
            val cache = temporary.newFolder()
            val repo = repository(cache, client)
            server.enqueue(MockResponse().setBody("x".repeat(bytes.size)))
            val asset = RuntimeAsset("x86_64", "runtime", hash, bytes.size.toLong(), server.url("/binary").toString())
            expectFailure<IllegalStateException> { repo.asset(asset) { } }
            assertTrue(cache.listFiles()!!.isEmpty())
            assertThrows(IllegalArgumentException::class.java) { RuntimeReleaseRepository.address("http://localhost/file") }
            assertThrows(IllegalArgumentException::class.java) { RuntimeReleaseRepository.address("https://key@localhost/file") }
        } finally { server.shutdown() }
    }
    @Test fun validatesHttpsManifestBeforeAnyAssetDownload() = runBlocking<Unit> {
        val (server, client) = server()
        try {
            val repo = repository(temporary.newFolder(), client)
            val asset = RuntimeAsset("x86_64", "runtime", hash, bytes.size.toLong(), server.url("/binary").toString())
            val release = RuntimeRelease("0.2.0", 1, listOf(asset))
            signed(server, release)
            assertEquals(release, repo.release(server.url("/manifest").toString(), 1))
            signed(server, release.copy(minAppVersionCode = 2))
            expectFailure<IllegalArgumentException> { repo.release(server.url("/manifest").toString(), 1) }
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun supportsHttpsAssetRedirectButRefusesHttpDowngrade() = runBlocking<Unit> {
        val (server, client) = server()
        try {
            val cache = temporary.newFolder()
            val repo = repository(cache, client)
            val asset = RuntimeAsset("x86_64", "runtime", hash, bytes.size.toLong(), server.url("/binary").toString())
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/final")))
            server.enqueue(MockResponse().setBody(String(bytes)))
            assertArrayEquals(bytes, repo.asset(asset) { }.readBytes())
            cache.listFiles()!!.forEach { it.delete() }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:${server.port}/insecure"))
            expectFailure<IllegalStateException> { repo.asset(asset) { } }
            assertEquals(3, server.requestCount)
            assertTrue(cache.listFiles()!!.isEmpty())
        } finally { server.shutdown() }
    }
    @Test fun cancellationClosesARequestWaitingForHeaders() = runBlocking<Unit> {
        val (server, client) = server()
        try {
            val repo = repository(temporary.newFolder(), client)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { repo.release(server.url("/manifest").toString(), 1) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { server.shutdown() }
    }
    @Test fun cancellationDeletesAPartialDownloadAndAllowsRetry() = runBlocking<Unit> {
        val (server, client) = server()
        try {
            val cache = temporary.newFolder()
            val repo = repository(cache, client)
            val asset = RuntimeAsset("x86_64", "runtime", hash, bytes.size.toLong(), server.url("/binary").toString())
            server.enqueue(MockResponse().setBody(String(bytes)).throttleBody(1, 1, TimeUnit.SECONDS))
            val receiving = CompletableDeferred<Unit>()
            val job = launch { repo.asset(asset) { receiving.complete(Unit) } }
            withTimeout(3000) { receiving.await() }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(cache.listFiles()!!.isEmpty())
            server.enqueue(MockResponse().setBody(String(bytes)))
            assertArrayEquals(bytes, repo.asset(asset) { }.readBytes())
        } finally { server.shutdown() }
    }
    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.java.name}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }
    @Test fun rejectsTamperedManifestAndWrongSigningIdentity() = runBlocking<Unit> {
        val (server, client) = server()
        try {
            val repo = repository(temporary.newFolder(), client)
            val asset = RuntimeAsset("x86_64", "runtime", hash, bytes.size.toLong(), server.url("/binary").toString())
            val release = RuntimeRelease("0.2.0", 1, listOf(asset))
            val original = Json.encodeToString(release).toByteArray()
            val signer = Signature.getInstance("SHA256withRSA").apply { initSign(signingKey.private); update(original) }
            server.enqueue(MockResponse().setBody(Json.encodeToString(release.copy(version = "9.0.0"))))
            server.enqueue(MockResponse().setBody(Buffer().write(signer.sign())))
            expectFailure<IllegalStateException> { repo.release(server.url("/manifest").toString(), 1) }
            val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val wrong = Signature.getInstance("SHA256withRSA").apply { initSign(other.private); update(original) }.sign()
            server.enqueue(MockResponse().setBody(Buffer().write(original)))
            server.enqueue(MockResponse().setBody(Buffer().write(wrong)))
            expectFailure<IllegalStateException> { repo.release(server.url("/manifest").toString(), 1) }
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun rejectsMissingOrMalformedSignatureBeforeAssetDownload() = runBlocking<Unit> {
        val (server, client) = server()
        try {
            val repo = repository(temporary.newFolder(), client)
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(404))
            expectFailure<IllegalStateException> { repo.release(server.url("/manifest").toString(), 1) }
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("not-a-signature"))
            expectFailure<IllegalStateException> { repo.release(server.url("/manifest").toString(), 1) }
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }
}
