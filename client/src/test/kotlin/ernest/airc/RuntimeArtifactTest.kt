package ernest.airc

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlin.test.*

class RuntimeArtifactTest {
    @Test fun fileDownloadsRequireExactDigestAndAllowEmptyFiles(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        val client = RuntimeArtifactClient()
        val id = "00000000-0000-4000-8000-000000000000"
        val data = "fixture".toByteArray()
        fun descriptor(bytes: ByteArray) = FileArtifact(id, "/artifacts/$id", "application/octet-stream", bytes.size.toLong(), "fixture.txt",
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }, 60)
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(data)))
            assertContentEquals(data, client.downloadFile(server.hostName, server.port, descriptor(data)))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(data)))
            assertFails { client.downloadFile(server.hostName, server.port, descriptor(data).copy(sha256 = "0".repeat(64))) }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(""))
            assertContentEquals(byteArrayOf(), client.downloadFile(server.hostName, server.port, descriptor(byteArrayOf())))
            assertFails { client.downloadFile(server.hostName, server.port, descriptor(data).copy(name = "../escape")) }
        } finally { client.close(); server.shutdown() }
        Unit
    }

    @Test fun downloadIsBoundedAndCannotFollowArbitraryPathsOrRedirects() = runBlocking {
        val server = MockWebServer(); server.start()
        val client = RuntimeArtifactClient()
        val png = ByteArray(33).also { byteArrayOf(137.toByte(),80,78,71,13,10,26,10).copyInto(it) }
        java.nio.ByteBuffer.wrap(png).apply { putInt(8, 13); putInt(12, 0x49484452); putInt(16, 1); putInt(20, 1) }
        val id = "00000000-0000-4000-8000-000000000000"
        val artifact = BinaryArtifact(id, "/artifacts/$id", "image/png", 33, 1, 1, 60)
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            assertContentEquals(png, client.download(server.hostName, server.port, artifact))
            assertEquals(artifact.path, server.takeRequest().path)
            assertFails { client.download(server.hostName, server.port, artifact.copy(path = "/status")) }
            assertFails { client.download(server.hostName, server.port, artifact.copy(sizeBytes = RuntimeArtifactClient.MAX_BYTES + 1)) }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/status"))
            assertFails { client.download(server.hostName, server.port, artifact) }
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setChunkedBody(Buffer().write(png + byteArrayOf(1)), 10))
            assertFails { client.download(server.hostName, server.port, artifact) }
            server.enqueue(MockResponse().setResponseCode(404))
            assertFails { client.download(server.hostName, server.port, artifact) }
        } finally { client.close(); server.shutdown() }
        Unit
    }
}
