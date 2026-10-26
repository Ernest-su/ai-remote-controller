package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class InstallationVerificationTest {
    @Test fun successfulTaskAutomaticallyQueriesInstalledIdentity() = verify("completed")
    @Test fun failedTaskRecoversWhenNewPackageIsActuallyInstalled() = verify("failed")
    @Test fun failedUpgradeRecoversOnlyAfterInstalledIdentityChanges() = verify("failed", previousVersion = 1)
    @Test fun failedSameVersionReinstallDoesNotMistakeOldPackageForSuccess() = verify("failed", previousVersion = 2, changed = false, succeeds = false)
    @Test fun queryRetriesTransientFailures() = verify("failed", queryFailures = 2)
    @Test fun wrongUserNeverBecomesSuccessful() = verify("failed", wrongUser = true, succeeds = false)
    @Test fun missingPackageReportsAutomaticQueryFailure() = verify("failed", queryFailures = 3, succeeds = false)

    private fun verify(phase: String, previousVersion: Long? = null, changed: Boolean = true, queryFailures: Int = 0, wrongUser: Boolean = false, succeeds: Boolean = true): Unit = runBlocking {
        val queries = AtomicInteger()
        val apk = TargetApkInfo("inbox", "fixture.apk", "fixture.app", 2, sizeBytes = 7, sha256 = "a".repeat(64), signatures = emptyList(), signatureVerified = false)
        val installed = TargetAppInfo("fixture.app", if (wrongUser) 10 else 0, 10001, 2, codePath = "/data/app/fixture", lastUpdateTime = if (changed) "new" else "old", system = false)
        val previous = previousVersion?.let { installed.copy(versionCode = it, lastUpdateTime = "old") }
        val proposal = AppInstallProposal("proposal", "app_install", apk, currentApp = previous, userId = 0, update = previous != null, approvalRequired = true, codeMayAffectAllUsers = previous != null)
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                val message = Json.parseToJsonElement(text).jsonObject
                val tool = message["tool"]?.jsonPrimitive?.content
                val failing = tool == "apps.info" && queries.incrementAndGet() <= queryFailures
                val result = when {
                    tool == "apps.install_pending" -> Json.encodeToString(listOf(proposal))
                    tool == "apps.info" -> Json.encodeToString(installed)
                    message["type"]?.jsonPrimitive?.content == "approve_install" -> Json.encodeToString(TransferProgress("install", "app_install", "fixture.app", 7, 7, phase, dataPath = "/transfers/install/data", root = "apps", unit = "bytes", error = if (phase == "failed") "query apps.info to confirm final installation state" else null,
                        result = if (phase == "completed") buildJsonObject { put("installed", true); put("package", apk.packageName); put("versionCode", 2); put("userId", 0); put("sha256", apk.sha256) } else null))
                    else -> error("Unexpected request: $message")
                }
                socket.send(buildJsonObject {
                    put("id", message.getValue("id")); put("protocol", 1); put("ok", !failing)
                    if (failing) put("error", "package not installed") else put("result", result)
                }.toString())
            }
        }))
        server.start()
        val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AppInstallationController(scope, runtime)
        try {
            runtime.connect(server.hostName, server.port)
            controller.pending(true)
            withTimeout(3_000) { controller.state.first { it.proposal != null } }
            controller.confirm(true)
            val result = withTimeout(5_000) { controller.state.first { !it.busy } }
            assertEquals(if (succeeds) "completed" else "failed", result.phase)
            assertEquals(if (succeeds) queryFailures + 1 else 3, queries.get())
            if (succeeds) { assertNull(result.error); assertNotNull(result.message) }
            else { assertNotNull(result.error); assertFalse(checkNotNull(result.error).contains("query apps.info")); assertNull(result.message) }
        } finally { controller.clear(); runtime.close(); scope.cancel(); server.shutdown() }
    }
}
