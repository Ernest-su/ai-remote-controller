package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class DesktopRuntimeAlignmentTest {
    @Test fun matchingServiceReusesLanWithoutAdb() = runBlocking { verify(initiallyMatches = true) }
    @Test fun sameVersionWithDifferentBytesAutomaticallyDeploysAndReconnects() = runBlocking { verify() }
    @Test fun failedUpgradeLeavesServiceDisconnected() = runBlocking { verify(deploymentFails = true) }
    @Test fun stillMismatchedAfterUpgradeIsRejectedWithoutRetryLoop() = runBlocking { verify(remainsOld = true) }

    private suspend fun verify(initiallyMatches: Boolean = false, deploymentFails: Boolean = false, remainsOld: Boolean = false) {
        val project = Files.createTempDirectory("airc-alignment-")
        val manifest = project.resolve("androidApp/src/main/assets/runtime/manifest.json")
        Files.createDirectories(manifest.parent)
        Files.writeString(manifest, Json.encodeToString(RuntimeRelease.serializer(), RuntimeRelease("0.1.0", 1,
            listOf(RuntimeAsset("arm64-v8a", "runtime", "a".repeat(64), 1)))))
        val fakeAdb = project.resolve("adb").toFile()
        fakeAdb.writeText("""#!/bin/sh
printf '%s\n' "${'$'}*" >> "${'$'}(dirname "${'$'}0")/adb-calls"
""")
        check(fakeAdb.setExecutable(true))
        project.resolve("android-build-run.sh").toFile().writeText(if (deploymentFails) "exit 1\n" else "touch \"${'$'}(dirname \"${'$'}0\")/upgraded\"\n")
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/status" -> {
                    val matched = initiallyMatches || (!remainsOld && Files.exists(project.resolve("upgraded")))
                    MockResponse().setBody(Json.encodeToString(RuntimeStatus.serializer(), RuntimeStatus("0.1.0", 1,
                        "fixture", emptyList(), binarySha256 = (if (matched) "a" else "b").repeat(64))))
                }
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = Json.parseToJsonElement(text).jsonObject
                        webSocket.send(buildJsonObject {
                            put("id", request["id"]!!); put("protocol", 1); put("ok", true); put("result", "")
                        }.toString())
                    }
                })
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = DesktopController(adb = HostAdb(fakeAdb.absolutePath), project = project, scope = scope,
            store = DesktopProfileStore(project), deviceNames = DesktopDeviceNames(project))
        try {
            controller.connectLan(server.hostName, server.port)
            withTimeout(5000) { controller.state.first { !it.busy } }
            assertEquals(!deploymentFails && !remainsOld, controller.state.value.connected)
            assertEquals(!initiallyMatches, Files.exists(project.resolve("adb-calls")))
            assertEquals(!initiallyMatches && !deploymentFails, Files.exists(project.resolve("upgraded")))
            if (remainsOld) assertTrue(controller.state.value.output.contains("不一致"))
        } finally { controller.close(); server.shutdown(); project.toFile().deleteRecursively() }
    }
}
