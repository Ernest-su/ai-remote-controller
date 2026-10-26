package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.*

class FileTextProtocolTest {
    @Test fun editsPreserveEncodingAndSaveOnlyAfterApprovalAndRejectChangedSources(): Unit = runBlocking {
        fixture { controller, directory, _ ->
            val path = File(directory, "state/files/code.unknown")
            val original = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "val x = 1\r\n中文".toByteArray(Charsets.UTF_16LE)
            path.writeBytes(original)
            controller.open("inbox", path.name, TargetFileEntry(path.name, "file", original.size.toLong()), true, true)
            withTimeout(5_000) { controller.state.first { it.text != null } }
            assertEquals("UTF-16LE", controller.state.value.encoding)
            controller.edit("val x = 2\r\n中文")
            controller.save(true)
            withTimeout(5_000) { controller.transfers.state.first { it.proposal != null } }
            assertContentEquals(original, path.readBytes())
            controller.transfers.confirm(true)
            val saved = withTimeout(5_000) { controller.state.first { !it.saving && it.message != null } }
            assertFalse(saved.dirty)
            assertContentEquals(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "val x = 2\r\n中文".toByteArray(Charsets.UTF_16LE), path.readBytes())
            controller.edit("unsaved change")
            path.writeText("changed elsewhere")
            controller.save(true)
            val conflict = withTimeout(5_000) { controller.state.first { !it.saving && it.error != null } }
            assertTrue(conflict.dirty); assertEquals("unsaved change", conflict.draft)
            assertEquals("changed elsewhere", path.readText())
            assertNull(controller.transfers.state.value.proposal)
        }
    }
    @Test fun largeFilesRequireConsentAndNeverSaveTruncatedDataAndReconnectCannotSaveOldDraft(): Unit = runBlocking {
        fixture { controller, directory, runtime ->
            val file = File(directory, "state/files/large.txt")
            file.writeBytes(ByteArray(TEXT_EDIT_BYTES + 100) { 'a'.code.toByte() })
            controller.open("inbox", file.name, TargetFileEntry(file.name, "file", file.length()), true, true)
            withTimeout(5_000) { controller.state.first { it.warning } }
            assertNull(controller.state.value.text)
            controller.continueLoading()
            val preview = withTimeout(5_000) { controller.state.first { it.text != null } }
            assertTrue(preview.partial); assertFalse(preview.writable); assertEquals(TEXT_WARNING_BYTES, preview.draft.length)
            controller.edit("must not save"); controller.save(true)
            assertEquals(TEXT_WARNING_BYTES, controller.state.value.draft.length)
            assertEquals(TEXT_EDIT_BYTES + 100L, file.length())
            controller.closeDocument()
            file.writeText("before")
            controller.open("inbox", file.name, TargetFileEntry(file.name, "file", 6), true, true)
            withTimeout(5_000) { controller.state.first { it.text != null } }
            controller.edit("after")
            runtime.disconnect()
            controller.save(true)
            withTimeout(5_000) { controller.state.first { !it.saving && it.error != null } }
            assertEquals("before", file.readText()); assertEquals("after", controller.state.value.draft)
        }
    }
    @Test fun encodingRoundTripsPreserveBomAndRejectIncompleteOrOversizedEdits() {
        for (encoding in listOf("UTF-8", "UTF-16LE", "UTF-16BE", "GB18030")) {
            val prefix = when (encoding) { "UTF-8" -> byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()); "UTF-16LE" -> byteArrayOf(0xff.toByte(), 0xfe.toByte()); "UTF-16BE" -> byteArrayOf(0xfe.toByte(), 0xff.toByte()); else -> ByteArray(0) }
            val source = prefix + "中文\r\nconst x = 1".toByteArray(java.nio.charset.Charset.forName(encoding))
            val decoded = decodeEditableText(source)
            assertContentEquals(source, encodeEditableText(decoded.text, decoded.encoding, textBom(source)))
        }
        assertFails { decodeEditableText(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x61)) }
        assertFails { encodeEditableText("中".repeat(TEXT_EDIT_BYTES), "UTF-8", ByteArray(0)) }
    }
    private suspend fun fixture(block: suspend (FileTextController, File, RuntimeConnection) -> Unit) {
        val directory = Files.createTempDirectory("airc-text-protocol-").toFile()
        val state = File(directory, "state")
        val port = ServerSocket(0).use { it.localPort }
        val binary = File(requireNotNull(System.getProperty("airc.hostRuntime")))
        val process = ProcessBuilder(binary.path, "serve", "--no-mdns", "--state-dir", state.path, "--port", port.toString()).redirectErrorStream(true).redirectOutput(File(directory, "runtime.log")).start()
        val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = FileTextController(scope, runtime)
        try {
            withTimeout(10_000) { while (true) { try { runtime.connect("127.0.0.1", port); break } catch (e: Exception) { delay(50) } } }
            block(controller, directory, runtime)
        } finally { controller.close(); runtime.close(); scope.cancel(); process.destroy(); process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); if(process.isAlive)process.destroyForcibly(); directory.deleteRecursively() }
    }
}
