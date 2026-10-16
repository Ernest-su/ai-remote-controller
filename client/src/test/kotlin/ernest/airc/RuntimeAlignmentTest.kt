package ernest.airc

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class RuntimeAlignmentTest {
    private val hash = "a".repeat(64)
    private val release = RuntimeRelease("0.1.0", 1, listOf(RuntimeAsset("arm64-v8a", "runtime", hash, 1)))
    private val status = RuntimeStatus("0.1.0", 1, "fixture", emptyList(), binarySha256 = hash)

    @Test fun identicalVersionAndRunningBytesAreRequired() {
        requireRuntimeAlignment(status, release)
        listOf(status.copy(version = "0.0.9"), status.copy(binarySha256 = "b".repeat(64)),
            status.copy(binarySha256 = null), status.copy(binarySha256 = "invalid"), status.copy(protocol = 2)
        ).forEach { assertFailsWith<RuntimeAlignmentRequired> { requireRuntimeAlignment(it, release) } }
    }

    @Test fun mismatchedServiceNeverOpensAWebSocketOrPublishesAConnectedSession(): Unit = runBlocking {
        val server = MockWebServer()
        val mismatches = listOf(status.copy(binarySha256 = "b".repeat(64)), status.copy(version = "0.0.9"),
            status.copy(protocol = 2), status.copy(binarySha256 = null))
        mismatches.forEach { server.enqueue(MockResponse().setBody(Json.encodeToString(RuntimeStatus.serializer(), it))) }
        server.start()
        val runtime = RuntimeConnection()
        try {
            mismatches.forEachIndexed { index, _ ->
                assertFailsWith<RuntimeAlignmentRequired> { runtime.connect(server.hostName, server.port, release) }
                assertFalse(runtime.connected.value)
                assertNull(runtime.session.value)
                assertEquals(index + 1, server.requestCount)
                assertEquals("/status", server.takeRequest().path)
            }
        } finally { runtime.close(); server.shutdown() }
    }
}
