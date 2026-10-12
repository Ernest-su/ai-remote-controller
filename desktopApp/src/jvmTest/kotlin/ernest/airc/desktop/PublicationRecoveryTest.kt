package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*
import java.nio.file.Files

class PublicationRecoveryTest {
    private val json=Json {ignoreUnknownKeys=true}
    @Test fun livePublicationSurvivesRuntimeRestartAndCanBeReleased():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for controlled runtime restart",!serial.isNullOrBlank())
        val target=ShellRuntimeTarget(serial!!)
        val runtime=RuntimeConnection();val transfer=RuntimeTransferClient(runtime)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val media=FileMediaController(scope,runtime)
        val directory=Files.createTempDirectory("airc-recovery-").toFile()
        val name="airc-media-recovery-${java.util.UUID.randomUUID()}.png"
        val source=java.io.File(directory,name).apply {check(javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8,8,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",this))}
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){(it.toInt() and 255).toString(16).padStart(2,'0')}
        var publication:MediaPublication?=null
        try {
            target.prepare()
            val before=runtime.connect("127.0.0.1",checkNotNull(target.port))
            assertEquals(2000,before.uid,"Only restart the shell runtime used by this fixture")
            transfer.upload(name,source.length(),digest,source::inputStream)
            var task=json.decodeFromString<MediaPublicationTask>(runtime.request("tool","media.publish",buildJsonObject {put("root","inbox");put("path",name)}))
            val taskId=task.id
            withTimeout(10000){while(task.phase !in listOf("completed","failed","cancelled")){delay(100);task=json.decodeFromString<MediaPublicationTask>(runtime.request("tool","task.progress",buildJsonObject{put("id",taskId)}));assertEquals(taskId,task.id)}}
            assertEquals("media_publish",task.direction);assertEquals("inbox",task.root);assertEquals(name,task.name)
            assertEquals("completed",task.phase,task.error);publication=checkNotNull(task.result)
            runtime.disconnect()
            target.restart()
            val after=runtime.connect("127.0.0.1",checkNotNull(target.port))
            assertEquals(before.deviceId,after.deviceId);assertEquals(before.modelConfigured,after.modelConfigured)
            assertFails { runtime.request("tool","task.progress",buildJsonObject{put("id",taskId)}) }
            val recovered=json.decodeFromString<List<MediaPublication>>(runtime.request("tool","media.publications"))
            assertEquals(publication,recovered.single{it.id==publication!!.id})
            media.recover(true)
            val recoveredState=withTimeout(5000){media.state.first{!it.busy}}
            assertNull(recoveredState.error)
            assertEquals(publication,recoveredState.retainedCopies.single())
            assertEquals(digest,publication!!.sha256)
            assertEquals(source.length(),publication!!.sizeBytes)
            assertContentEquals(source.readBytes(),runtime.downloadFile(publication!!.path).second)
            media.releaseCopy(publication!!.id,true)
            val releasedState=withTimeout(5000){media.state.first{!it.busy}}
            assertNull(releasedState.error);assertTrue(releasedState.retainedCopies.isEmpty())
            assertTrue(json.decodeFromString<List<MediaPublication>>(runtime.request("tool","media.publications")).none{it.id==publication!!.id})
            assertFails{runtime.downloadFile(publication!!.path)}
            assertContentEquals(source.readBytes(),runtime.downloadFile(name,"inbox").second)
            publication=null
        } finally {
            publication?.let {copy->runCatching{runtime.connect("127.0.0.1",checkNotNull(target.port));runtime.request("tool","media.release",buildJsonObject{put("id",copy.id)})}}
            media.clear();scope.cancel();transfer.close();runtime.close()
            try { target.close() } finally { directory.deleteRecursively() }
        }
    }
}
