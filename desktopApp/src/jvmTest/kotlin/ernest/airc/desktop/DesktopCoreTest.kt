package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList

class DesktopCoreTest {
    @Test fun screenshotSavePreservesExistingFiles() {
        val directory = Files.createTempDirectory("airc-screenshot-save-")
        try {
            val file = directory.resolve("screenshot.png")
            DesktopScreenshotFiles.save(file, byteArrayOf(1, 2))
            assertFails { DesktopScreenshotFiles.save(file, byteArrayOf(3)) }
            assertContentEquals(byteArrayOf(1, 2), Files.readAllBytes(file))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun deviceNamesPersistByIdentityAndRejectCorruptStorage() {
        val directory = Files.createTempDirectory("airc-device-names-")
        try {
            DesktopDeviceNames(directory).rename("uuid", " 客厅 ")
            DesktopDeviceNames(directory).rename("other", "卧室")
            assertEquals(mapOf("uuid" to "客厅", "other" to "卧室"), DesktopDeviceNames(directory).read())
            assertFails { DesktopDeviceNames(directory).rename("uuid", " ") }
            Files.writeString(directory.resolve("device-names.json"), "broken")
            assertFails { DesktopDeviceNames(directory).rename("uuid", "覆盖") }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun encryptedStoreRoundTripDeletionAndCorruptionAreExplicit() {
        val directory = Files.createTempDirectory("airc-desktop-models-")
        try {
            val store = DesktopProfileStore(directory)
            val profile = ModelProfile("https://api.test/v1", "fixture-key", "Model-A", listOf("Model-A"), 123)
            val snapshot = ModelProfileSnapshot(listOf(profile), profile.baseUrl)
            store.write(snapshot)
            assertEquals(snapshot, DesktopProfileStore(directory).read())
            assertFalse(Files.readAllBytes(directory.resolve("models.enc")).toString(Charsets.UTF_8).contains("fixture-key"))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("models.key"))))
            store.write(ModelProfileSnapshot())
            assertTrue(store.read().profiles.isEmpty())
            Files.write(directory.resolve("models.enc"), byteArrayOf(1,2,3))
            assertFails { store.read() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun runtimeFixtureExercisesDirectToolsAndOnlyManualProfileSync() = runBlocking {
        val directory = Files.createTempDirectory("airc-desktop-test-")
        val calls = CopyOnWriteArrayList<RuntimeRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/status" -> MockResponse().setBody(alignedRuntimeStatusFixture("""{"version":"fixture","protocol":1,"deviceId":"desktop-fixture","tools":["device.info","remote.key"],"uid":2000}"""))
                "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(socket: WebSocket, text: String) {
                        val request = Json.decodeFromString<RuntimeRequest>(text); calls.add(request)
                        socket.send(buildJsonObject {
                            put("id", request.id); put("ok", true); put("protocol", 1)
                            put("result", if (request.tool == "device.info") """{"model":"Fixture TV","sdk":34}""" else "")
                        }.toString())
                    }
                })
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = DesktopController(store = DesktopProfileStore(directory), scope = scope, catalog = ModelCatalog { listOf("Model-A") })
        try {
            controller.connectLan("127.0.0.1", server.port)
            withTimeout(5000) { controller.state.first { it.connected && !it.busy } }
            controller.deviceInfo()
            withTimeout(5000) { controller.state.first { it.deviceInfo != null } }
            assertEquals(34, controller.state.value.deviceInfo?.sdk)
            controller.profiles.editUrl("https://api.test/v1"); controller.profiles.editKey("fixture-key"); controller.profiles.editModel("manual-model")
            controller.profiles.fetch()
            withTimeout(5000) { controller.profiles.state.first { !it.fetching && it.profiles.isNotEmpty() } }
            assertFalse(calls.any { it.type == "configure" })
            controller.syncProfile()
            withTimeout(5000) { controller.state.first { it.modelConfigured } }
            assertEquals(1, calls.count { it.type == "configure" })
        } finally { controller.close(); server.shutdown(); directory.toFile().deleteRecursively() }
    }

}
