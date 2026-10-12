package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*
import java.nio.file.Files

/** Valid PCM WAV verifies publication streams; the test never starts playback. */
class LargePublicationTargetTest {
    private val taskJson=Json {ignoreUnknownKeys=true}
    @Test fun liveLargeFileStreamingUploadAndDownload(): Unit = runBlocking {
        val serial = System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for controlled large-file transfer validation", !serial.isNullOrBlank())
        val target = ShellRuntimeTarget(serial!!)
        val runtime = RuntimeConnection(); val transfer = RuntimeTransferClient(runtime)
        val directory = Files.createTempDirectory("airc-large-stream-").toFile()
        val source = java.io.File(directory, "source.wav")
        val name = "airc-large-stream-${java.util.UUID.randomUUID()}.wav"
        var largePublication:String?=null
        val size = 17L * 1024 * 1024
        val buffer = ByteArray(64*1024) { it.toByte() }
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        val header=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt((size-8).toInt()).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1.toShort()).putShort(2.toShort()).putInt(44100).putInt(176400)
            .putShort(4.toShort()).putShort(16.toShort()).put("data".toByteArray()).putInt((size-44).toInt()).array()
        source.outputStream().use { output ->
            output.write(header);hash.update(header)
            var remaining=size-header.size
            while(remaining>0){val count=minOf(remaining,buffer.size.toLong()).toInt();output.write(buffer,0,count);hash.update(buffer,0,count);remaining-=count}
        }
        val audio=javax.sound.sampled.AudioSystem.getAudioFileFormat(source)
        assertEquals(2,audio.format.channels);assertEquals(16,audio.format.sampleSizeInBits)
        assertEquals((size-44)/4,audio.frameLength.toLong())
        val digest = hash.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
        try {
            target.prepare()
            runtime.connect("127.0.0.1", checkNotNull(target.port))
            val sent = withTimeout(180000) { transfer.upload(name, size, digest, { source.inputStream() }) }
            assertEquals("completed", sent.phase)
            val received = withTimeout(180000) { transfer.download(name, directory, root = "inbox") }
            assertEquals(name, received.first); assertEquals(size, received.second.length())
            val actual=java.security.MessageDigest.getInstance("SHA-256")
            received.second.inputStream().use { input -> while(true) { val count=input.read(buffer);if(count<0)break;actual.update(buffer,0,count) } }
            assertEquals(digest,actual.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') })
            var published=taskJson.decodeFromString<MediaPublicationTask>(runtime.request("tool","media.publish",buildJsonObject {put("root","inbox");put("path",name)}))
            withTimeout(180000) {while(published.phase !in listOf("completed","failed","cancelled")){delay(300);published=taskJson.decodeFromString<MediaPublicationTask>(runtime.request("tool","task.progress",buildJsonObject {put("id",published.id)}))}}
            assertEquals("completed",published.phase)
            val copy=checkNotNull(published.result);largePublication=copy.id
            assertEquals(size,copy.sizeBytes);assertEquals(digest,copy.sha256)
            val publishedDownload=transfer.download(copy.path,directory,"downloads")
            assertEquals(size,publishedDownload.second.length())
            val copiedHash=java.security.MessageDigest.getInstance("SHA-256")
            publishedDownload.second.inputStream().use { input->while(true){val count=input.read(buffer);if(count<0)break;copiedHash.update(buffer,0,count)} }
            assertEquals(digest,copiedHash.digest().joinToString(""){(it.toInt() and 255).toString(16).padStart(2,'0')})
            assertTrue(taskJson.parseToJsonElement(runtime.request("tool","media.release",buildJsonObject {put("id",copy.id)})).jsonObject["released"]!!.jsonPrimitive.boolean)
            assertFails { runtime.request("tool","files.stat",buildJsonObject{put("root","downloads");put("path",copy.path)}) }
            val sourceStat=taskJson.parseToJsonElement(runtime.request("tool","files.stat",buildJsonObject{put("root","inbox");put("path",name)})).jsonObject
            assertEquals(size,sourceStat["sizeBytes"]!!.jsonPrimitive.long)
            assertEquals(digest,HostAdb().command("-s",serial,"shell","sha256sum '${target.inboxPath}/$name'").trim().substringBefore(' '))
            largePublication=null

        } finally {
            largePublication?.let {id->runCatching{runtime.request("tool","media.release",buildJsonObject {put("id",id)})}}
            transfer.close(); runtime.close()
            try { target.close() } finally { directory.deleteRecursively() }
        }
    }
}
