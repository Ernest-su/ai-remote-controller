package ernest.airc

import androidx.test.platform.app.InstrumentationRegistry
import ernest.ascrcpy.scrcpy.ScrcpyTextInput
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Requires an explicitly provided, owned target with the disposable media fixture installed. */
class ScrcpyTextInputTargetTest {
    @Test fun cancelledTargetCheckRemovesTheUploadedServer() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("textTargetHost")
        val port = args.getString("textTargetPort")?.toIntOrNull()
        assumeTrue("Explicit owned target required", host != null && port != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = AdbConnector(context).tcp(host!!, port!!)
        val command = "find /data/local/tmp -maxdepth 1 -name 'airc-text-*.jar' -print"
        try {
            val before = session.client.shell(command).text().trim()
            var cancelled = false
            try {
                withContext(Dispatchers.IO) {
                    context.assets.open("scrcpy-server-v4.0").use { server ->
                        ScrcpyTextInput(session.client).paste(server, "synthetic cancelled text") { throw CancellationException("fixture target changed") }
                    }
                }
            } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertEquals(before, session.client.shell(command).text().trim())
        } finally { session.close() }
    }
    @Test fun clipboardPasteDeliversChineseAndLiteralShellCharacters() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("textTargetHost")
        val port = args.getString("textTargetPort")?.toIntOrNull()
        assumeTrue("Explicit owned target required", host != null && port != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = AdbConnector(context).tcp(host!!, port!!)
        val text = "中文片名 %s; \$(reboot) 🎬"
        val command = "find /data/local/tmp -maxdepth 1 -name 'airc-text-*.jar' -print"
        val before = session.client.shell(command).text().trim()
        try {
            val launch = session.client.shell("am start -W -n ernest.airc.mediafixture/.TextActivity --ez paste true").text()
            assertTrue(launch.contains("Status: ok"))
            delay(500)
            withContext(Dispatchers.IO) {
                withTimeout(30000) {
                    context.assets.open("scrcpy-server-v4.0").use { ScrcpyTextInput(session.client).paste(it, text) {} }
                }
            }
            var actual = ""
            withTimeoutOrNull(5000) {
                while (actual != text) {
                    actual = session.client.shell("content read --uri content://ernest.airc.mediafixture.text/text").text().trim()
                    if (actual != text) delay(100)
                }
            }
            assertEquals(text, actual)
            assertEquals(before, session.client.shell(command).text().trim())
        } finally { session.close() }
    }
}
