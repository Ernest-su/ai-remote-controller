package ernest.airc

import ernest.ascrcpy.adb.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.*
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

class RuntimeUploaderTest {
    @get:Rule val files = TemporaryFolder()
    private val bytes = ByteArray(256 * 1024) { (it % 13).toByte() }
    private val privilege = RuntimePrivilege(RuntimePrivilege.Mode.SHELL, false)
    private val asset get() = RuntimeAsset("fixture", "runtime", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size.toLong())
    private class Client(var supportsGzip: Boolean = true, var extractionFails: Boolean = false, val requiresBusybox: Boolean = false) : AdbClient {
        override val state = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
        val stored = mutableMapOf<String,ByteArray>()
        val commands = mutableListOf<String>()
        var blockPush: CompletableDeferred<Unit>? = null
        override suspend fun shell(command: String): AdbCommandResult {
            commands += command
            val result = when {
                command.contains(" | ") -> if (supportsGzip && (!requiresBusybox || command.startsWith("/data/adb/magisk/busybox printf"))) "airc-gzip-probe" else ""
                command.startsWith("rm -f ") -> { stored.remove(command.substringAfter("rm -f ").trim('\'')); "" }
                command.contains("airc-extracted") -> {
                    val paths = Regex("'([^']+)'").findAll(command).map { it.groupValues[1] }.toList()
                    if (extractionFails) "gzip: failed" else {
                        stored[paths[1]] = GZIPInputStream(stored.getValue(paths[0]).inputStream()).use { it.readBytes() }
                        "airc-extracted\n"
                    }
                }
                else -> error(command)
            }
            return AdbCommandResult(result.toByteArray())
        }
        override suspend fun push(source: InputStream, remotePath: String, mode: Int) {
            stored[remotePath] = source.readBytes()
            blockPush?.let { it.complete(Unit); awaitCancellation() }
        }
        override suspend fun connect(endpoint: AdbEndpoint): AdbDevice = error("unused")
        override suspend fun disconnect() {}
        override suspend fun pull(remotePath: String, destination: OutputStream) = error("unused")
        override suspend fun open(service: String): AdbChannel = error("unused")
        override fun close() {}
    }
    @Test fun missingDecoderPushesOriginalBytesWithoutCreatingCompressionCache() = runBlocking {
        val cache = File(files.root, "cache")
        val client = Client(supportsGzip = false)
        assertFalse(RuntimeUploader(cache).upload(client, asset, bytes::inputStream, "/target.next", privilege) {})
        assertArrayEquals(bytes, client.stored.getValue("/target.next"))
        assertFalse(cache.exists())
        assertEquals(8, client.commands.size)
    }
    @Test fun compressedUploadCachesVerifiedBytesAndCleansRemoteArchive() = runBlocking {
        val cache = files.newFolder()
        val client = Client()
        var reads = 0
        val uploader = RuntimeUploader(cache)
        repeat(2) {
            assertTrue(uploader.upload(client, asset, { reads++; bytes.inputStream() }, "/target.next", privilege) {})
            assertArrayEquals(bytes, client.stored.getValue("/target.next"))
            assertEquals(setOf("/target.next"), client.stored.keys)
        }
        assertEquals("Warm cache must not reread the source",1,reads)
        cache.listFiles()!!.single().writeText("broken cache")
        assertTrue(uploader.upload(client, asset, { reads++; bytes.inputStream() }, "/target.next", privilege) {})
        assertEquals(2,reads)
        assertArrayEquals(bytes,client.stored.getValue("/target.next"))
    }
    @Test fun existingBusyboxUsesItsOwnPrintfForLegacyShellEscapes() = runBlocking {
        val client = Client(requiresBusybox = true)
        assertTrue(RuntimeUploader(files.newFolder()).upload(client,asset,bytes::inputStream,"/target.next",privilege) {})
        assertArrayEquals(bytes,client.stored.getValue("/target.next"))
        assertTrue(client.commands.any { it.startsWith("/data/adb/magisk/busybox gzip -dc <") })
    }
    @Test fun negligibleCompressionGainUsesTheOriginalFile() = runBlocking {
        val small = ByteArray(16) { it.toByte() }
        val descriptor = asset.copy(size = small.size.toLong(), sha256 = MessageDigest.getInstance("SHA-256").digest(small).joinToString("") { "%02x".format(it) })
        val client = Client()
        assertFalse(RuntimeUploader(files.newFolder()).upload(client,descriptor,small::inputStream,"/target.next",privilege) {})
        assertArrayEquals(small,client.stored.getValue("/target.next"))
        assertFalse(client.commands.any { it.contains("airc-extracted") })
    }
    @Test fun failedExtractionReportsFailureAndAlwaysDeletesTheArchive() = runBlocking {
        val client = Client(extractionFails = true)
        try {
            RuntimeUploader(files.newFolder()).upload(client,asset,bytes::inputStream,"/target.next",privilege) {}
            fail("Expected extraction failure")
        } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("解压失败")) }
        assertTrue(client.stored.isEmpty())
    }
    @Test fun cancelledPushStillDeletesItsArchive() = runBlocking {
        val client = Client();val started = CompletableDeferred<Unit>();client.blockPush=started
        val job = launch { RuntimeUploader(files.newFolder()).upload(client,asset,bytes::inputStream,"/target.next",privilege) {} }
        started.await();job.cancelAndJoin()
        assertTrue(client.stored.isEmpty())
    }
}
