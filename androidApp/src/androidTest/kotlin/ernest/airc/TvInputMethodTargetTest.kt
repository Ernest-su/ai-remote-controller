package ernest.airc

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.InputStream

/** Explicit opt-in, owned API 31+ emulator only. Refuses to overwrite existing target packages. */
class TvInputMethodTargetTest {
    @Test fun matchingImeCommitsUnicodeWithoutClipboardAndRestoresPreviousKeyboard() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit owned emulator required", InstrumentationRegistry.getArguments().getString("ownedImeTarget") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val device = object : TvImeDevice {
            override suspend fun shell(command: String): String {
                // UiAutomation executes argv directly; feed shell syntax through sh's stdin.
                val pipes = automation.executeShellCommandRw("sh")
                ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).bufferedWriter().use { it.write(command); it.newLine() }
                return ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
            }
            override suspend fun push(input: InputStream, path: String) {
                val pipes = automation.executeShellCommandRw("dd of=$path")
                try { ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { input.copyTo(it) } }
                finally { ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() } }
            }
        }
        withContext(Dispatchers.IO) {
            check(!device.shell("pm path ernest.airc.ime").contains("package:")) { "Existing IME must be preserved" }
            check(device.shell("pm path ernest.airc.mediafixture").contains("package:")) { "Install owned mediaFixture first" }
            val original = device.shell("settings get secure default_input_method").trim()
            check(original.matches(Regex("[A-Za-z0-9_.$/]+"))) { "Original keyboard must be restorable" }
            val enabled = device.shell("settings get secure enabled_input_methods").trim()
            val filesBefore = device.shell("find /data/local/tmp -maxdepth 1 -name 'airc-ime-*.apk' -print").trim()
            val transport = TvInputMethodTransport(context, device) {}
            val focusScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val input = TvInputController(focusScope, { "native-fixture" }, { "native-fixture" },
                { TvInputMode.IME }, { _, _ -> }, {
                    object : TvInputBackend {
                        override suspend fun status() = transport.status()
                        override suspend fun focusedEditor() = transport.focusedEditor()
                        override suspend fun enable(): TvImeStatus = error("Monitoring must not enable IME")
                        override suspend fun restore(): TvImeStatus = error("Monitoring must not restore IME")
                        override suspend fun commit(text: String): TvImeStatus = error("Monitoring must not submit")
                        override suspend fun paste(text: String) = error("Monitoring must not paste")
                        override fun close() = Unit
                    }
                })
            var monitor: Job? = null
            try {
                assertFalse(transport.status().installed)
                val switched = transport.enable()
                assertTrue(switched.aligned && switched.selected)
                assertEquals(original, switched.previous)
                // Enabling an already selected IME must not replace the saved original.
                assertEquals(original, transport.enable().previous)
                monitor = focusScope.launch { input.monitorFocus(150, 250) }
                device.shell("am start -W -n ernest.airc.mediafixture/.TextActivity --ez ime true --es clipboard airc-fixture-clipboard")
                withTimeout(10000) { while (!transport.focusedEditor().ready) delay(100) }
                assertTrue(transport.focusedEditor().selected)
                withTimeout(10000) { while (input.focusedEditor.value == null) delay(100) }
                val text = "中文片名 🎬 %s; \$(reboot) ' 字面文本"
                transport.commit(text)
                var actual = ""
                withTimeout(5000) {
                    do {
                        actual = device.shell("content read --uri content://ernest.airc.mediafixture.text/text").trim()
                        if (actual != text) delay(100)
                    } while (actual != text)
                }
                assertEquals(text, actual)
                assertTrue(device.shell("content call --uri content://ernest.airc.mediafixture.text --method clipboard")
                    .contains("clipboard=airc-fixture-clipboard"))
                // A foreground app without ADB privileges cannot send text through the provider.
                var denied = false
                try { context.contentResolver.call(android.net.Uri.parse("content://ernest.airc.ime.control"), "commit", null, android.os.Bundle()) }
                catch (_: SecurityException) { denied = true }
                assertTrue(denied)
                device.shell("content call --uri content://ernest.airc.mediafixture.text --method restoreClipboard")
                device.shell("am force-stop ernest.airc.mediafixture")
                device.shell("am start -W -n ernest.airc.mediafixture/.TextActivity")
                withTimeout(5000) { while (transport.focusedEditor().ready) delay(100) }
                withTimeout(5000) { while (input.focusedEditor.value != null) delay(100) }
                monitor.cancelAndJoin()
                assertNull(input.focusedEditor.value)
                val failure = runCatching { transport.commit("must not be inserted") }.exceptionOrNull()
                assertNotNull("No focused editor must be rejected", failure)
                assertEquals(text, device.shell("content read --uri content://ernest.airc.mediafixture.text/text").trim())
                assertFalse(transport.restore().selected)
                assertEquals(original, device.shell("settings get secure default_input_method").trim())
                assertEquals(filesBefore, device.shell("find /data/local/tmp -maxdepth 1 -name 'airc-ime-*.apk' -print").trim())
            } finally {
                monitor?.cancelAndJoin()
                focusScope.cancel()
                device.shell("content call --uri content://ernest.airc.mediafixture.text --method restoreClipboard")
                device.shell("ime set '${original.replace("'", "'\\''")}'")
                device.shell("ime disable $TV_IME")
                device.shell("pm uninstall ernest.airc.ime")
                assertEquals(original, device.shell("settings get secure default_input_method").trim())
                assertEquals(enabled, device.shell("settings get secure enabled_input_methods").trim())
            }
        }
    }
}
