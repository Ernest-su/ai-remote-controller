package ernest.airc

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class DeploymentRollbackIntegrationTest {
    @Test fun targetRestoresHealthyServiceWhenCandidateCannotStart() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("targetHost") ?: "10.0.2.2"
        val adbPort = args.getString("targetAdbPort")?.toInt() ?: 5557
        val port = args.getString("runtimePort")?.toInt() ?: 9876
        val session = AdbConnector(instrumentation.targetContext).tcp(host, adbPort)
        val installer = RuntimeInstaller(instrumentation.targetContext)
        val connection = RuntimeConnection()
        val binary = RuntimeInstaller.BINARY
        val directory = RuntimeInstaller.DIRECTORY
        var backedUp = false
        var succeeded = false
        try {
            installer.install(session.client, runtimePort = port) { }
            installer.start(session.client, port)
            val before = connection.connect(host, port)
            val original = session.client.shell("sha256sum $binary").text()
            session.client.shell("cp -p $binary $binary.test-backup")
            backedUp = true
            val bad = "#!/system/bin/sh\nif [ \"\${2}\" = check-port ]; then exec $binary.test-backup \"\${@}\"; fi\nif [ \"\${1}\" = --version ]; then echo ${before.version}; exit 0; fi\necho fixture-startup-failed\nexit 42\n"
            session.client.push(ByteArrayInputStream(bad.toByteArray()), "$binary.next", 0x1C0)
            val output = RuntimeDeploymentRunner.run(directory, port,
                RuntimePrivilege(RuntimePrivilege.Mode.SHELL, false),
                push = { bytes, path -> bytes.inputStream().use { session.client.push(it, path, 0x180) } },
                shell = { session.client.shell(it).text() })
            assertTrue(output, output.lineSequence().any { it == "airc-rolled-back" })
            assertEquals(original, session.client.shell("sha256sum $binary").text())
            val after = connection.connect(host, port)
            assertEquals(before.deviceId, after.deviceId)
            assertEquals(before.version, after.version)
            val status = Json.parseToJsonElement(installer.status(session.client)).jsonObject
            assertEquals("true", status.getValue("ready").toString())
            succeeded = true
        } finally {
            if (backedUp) {
                if (!succeeded) {
                    session.client.shell("$binary.test-backup service stop --state-dir $directory/state")
                    session.client.shell("mv $binary.test-backup $binary")
                    installer.start(session.client, port)
                } else session.client.shell("rm -f $binary.test-backup")
            }
            connection.close(); session.close()
        }
    }
}
