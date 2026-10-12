package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import kotlin.test.*

class NetworkDiagnosticsTest {
    @Test fun cancellationStopsWaitingProbeAndReportsUnfinishedStages() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val diagnostic = NetworkDiagnostics(scope)
            diagnostic.run(server.hostName, server.port)
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) })
            diagnostic.cancel()
            val result = withTimeout(5000) { diagnostic.state.first { !it.running } }
            assertTrue(result.stages.any { it.detail.contains("已取消") })
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(503))
            diagnostic.run(server.hostName,server.port)
            val retried=withTimeout(5000){diagnostic.state.first{!it.running&&it.stages.size==4}}
            assertFalse(retried.stages.any{it.detail.contains("已取消")})
            assertEquals(listOf(DiagnosticOutcome.SKIPPED,DiagnosticOutcome.FAILED,DiagnosticOutcome.SKIPPED,DiagnosticOutcome.SKIPPED),retried.stages.map{it.outcome})
            assertEquals(2,server.requestCount)
        } finally { scope.cancel(); server.shutdown() }
    }
    @Test fun separateProbesReportHttpWebSocketAndOptionalPlatforms() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when(request.path) {
                "/status" -> MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"diagnostic-fixture","tools":[]}""")
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val active = RuntimeConnection()
        try {
            active.connect(server.hostName, server.port)
            val diagnostic = NetworkDiagnostics(scope)
            diagnostic.run(server.hostName, server.port, { "UID 2000" }) {
                DiagnosticStage("mDNS", DiagnosticOutcome.PASSED, null, "匹配公告")
            }
            val result = withTimeout(5000) { diagnostic.state.first { !it.running && it.stages.size == 4 } }
            assertTrue(result.stages.all { it.outcome == DiagnosticOutcome.PASSED })
            assertTrue(result.stages.all { it.elapsedMs != null && it.elapsedMs!! >= 0 })
            assertTrue(active.connected.value)
        } finally { active.close(); scope.cancel(); server.shutdown() }
    }
    @Test fun failedStatusSkipsWebSocketAndDoesNotPretendToTestMdns() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(503)); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val diagnostic = NetworkDiagnostics(scope)
            diagnostic.run(server.hostName, server.port)
            val result = withTimeout(5000) { diagnostic.state.first { !it.running && it.stages.size == 4 } }
            assertEquals(listOf(DiagnosticOutcome.SKIPPED, DiagnosticOutcome.FAILED, DiagnosticOutcome.SKIPPED, DiagnosticOutcome.SKIPPED), result.stages.map { it.outcome })
            assertEquals(1, server.requestCount)
        } finally { scope.cancel(); server.shutdown() }
    }
}
