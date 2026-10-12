package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class DeviceInformationTargetTest {
    @Test fun liveTargetReadOnlyInfoAndScreenshotThroughHostAdbForward(): Unit = runBlocking {
        val serial = System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial to opt into real-device JVM validation", !serial.isNullOrBlank())
        val adb = HostAdb()
        assertTrue(serial in adb.devices())
        val target = ShellRuntimeTarget(serial!!,mdns=true)
        val names = Files.createTempDirectory("airc-live-device-names-")
        val runtime = RuntimeConnection()
        val fixtureName = "airc-download-fixture-${java.util.UUID.randomUUID()}.txt"
        val fixturePath = "/storage/emulated/0/Download/$fixtureName"
        var fixtureCreated = false
        try {
            target.prepare()
            val port=checkNotNull(target.port)
            adb.command("-s", serial, "shell", "set -C; printf 'airc-owned-download-fixture' > '$fixturePath'")
            fixtureCreated = true
            val status = runtime.connect("127.0.0.1", port)
            assertTrue("device.info" in status.tools)
            val info = Json.decodeFromString<DeviceInfo>(runtime.request("tool", "device.info"))
            val originalModel=adb.command("-s",serial,"shell","getprop ro.product.model").trim()
            assertEquals(originalModel,info.model)
            assertEquals(adb.command("-s",serial,"shell","getprop ro.build.version.sdk").trim().toInt(),info.sdk)
            DesktopDeviceNames(names).rename(status.deviceId,"验收显示名")
            assertEquals("验收显示名",DesktopDeviceNames(names).read()[status.deviceId])
            assertEquals(originalModel,adb.command("-s",serial,"shell","getprop ro.product.model").trim())
            assertNotNull(info.storage)
            assertTrue("files.download" in status.tools)
            val download = runtime.downloadFile(fixtureName)
            assertEquals(fixtureName, download.first)
            assertContentEquals("airc-owned-download-fixture".toByteArray(), download.second)
            assertTrue("files.list" in status.tools && "files.stat" in status.tools)
            val files = Json.decodeFromString<TargetFilesPage>(runtime.request("tool", "files.list", buildJsonObject { put("limit", 2) }))
            assertEquals("downloads", files.root)
            assertTrue(files.entries.size <= 2)
            val folder = Json.decodeFromString<TargetFileStat>(runtime.request("tool", "files.stat"))
            assertEquals("directory", folder.kind)
            files.nextCursor?.let { cursor ->
                val next = Json.decodeFromString<TargetFilesPage>(runtime.request("tool", "files.list", buildJsonObject { put("limit", 2); put("cursor", cursor) }))
                assertTrue(next.entries.none { entry -> files.entries.any { it.name == entry.name } })
            }
            assertTrue("device.screenshot" in status.tools)
            val png = runtime.screenshot()
            val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png))
            assertNotNull(image)
            assertTrue(image.width > 0 && image.height > 0)
            assertTrue(png.size <= RuntimeArtifactClient.MAX_BYTES)
            val diagnostics = NetworkDiagnostics(this)
            diagnostics.run("127.0.0.1", port, { adb.command("-s", serial, "shell", "id -u").trim() }, discoveryTimeoutMs = 12000) {
                DesktopDiscovery().probe(checkNotNull(DesktopDiscovery.tcpAdbHost(serial)), checkNotNull(target.runtimePort))
            }
            val report = withTimeout(20000) { diagnostics.state.first { !it.running && it.stages.size == 4 } }
            assertTrue(report.stages.take(3).all { it.outcome == DiagnosticOutcome.PASSED })
            assertEquals(DiagnosticOutcome.PASSED, report.stages.last().outcome, report.stages.last().detail)
            assertTrue(runtime.connected.value)
        } finally {
            runtime.close()
            try { if (fixtureCreated) adb.command("-s", serial, "shell", "rm -- '$fixturePath'") }
            finally { try{target.close()}finally{names.toFile().deleteRecursively()} }
        }
    }
}
