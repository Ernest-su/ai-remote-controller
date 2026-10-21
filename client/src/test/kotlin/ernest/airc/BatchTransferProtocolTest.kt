package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class BatchTransferProtocolTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun realRuntimeUploadsMultipleFilesAndDownloadsVerifiedZip()=runBlocking {
        val binary=File(requireNotNull(System.getProperty("airc.hostRuntime")));val port=ServerSocket(0).use {it.localPort}
        val state=temporary.newFolder("state");val log=temporary.newFile("runtime.log")
        val process=ProcessBuilder(binary.path,"serve","--no-mdns","--state-dir",state.path,"--port",port.toString()).redirectErrorStream(true).redirectOutput(log).start()
        val runtime=RuntimeConnection();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val controller=BatchTransferController(scope,runtime,temporary.newFolder("cache"))
        val first=ByteArray(5*1024*1024+41){(it%251).toByte()};val second="中文文件内容".encodeToByteArray()
        try {
            withTimeout(10000){while(runCatching {runtime.connect("127.0.0.1",port)}.isFailure){check(process.isAlive){log.readText()};delay(50)}}
            controller.select(listOf(UploadSource("first.bin",first.size.toLong()){first.inputStream()},UploadSource("电视.txt",second.size.toLong()){second.inputStream()}))
            assertTrue(controller.state.value.uploadSelection)
            controller.upload("inbox","")
            val uploaded=withTimeout(20000){controller.state.first {!it.running}}
            assertNull(uploaded.error);assertFalse(uploaded.paused)
            assertContentEquals(first,File(state,"files/first.bin").readBytes());assertContentEquals(second,File(state,"files/电视.txt").readBytes())
            controller.download("inbox","",listOf("first.bin","电视.txt"))
            val downloaded=withTimeout(20000){controller.state.first {!it.running}}
            assertNull(downloaded.error);assertTrue(downloaded.readyToSave);assertFalse(downloaded.uploadSelection)
            val uploadDraft=UploadDraftController()
            var openedSources=0
            val originalTask=downloaded.noticeId
            uploadDraft.select(listOf(UploadSource("next.txt",1) { openedSources++; byteArrayOf(1).inputStream() }))
            uploadDraft.select(listOf(UploadSource("next.txt",1) { openedSources++; byteArrayOf(1).inputStream() },UploadSource("other.txt",1) { openedSources++; byteArrayOf(2).inputStream() }))
            assertTrue(controller.state.value.readyToSave);assertEquals(originalTask,controller.state.value.noticeId)
            assertEquals(0,openedSources)
            ZipFile(checkNotNull(controller.exportFile())).use {zip->
                assertContentEquals(first,zip.getInputStream(zip.getEntry("first.bin")).readBytes())
                assertContentEquals(second,zip.getInputStream(zip.getEntry("电视.txt")).readBytes())
            }
            controller.download("inbox", "", listOf("first.bin", "电视.txt"), BatchDownloadMode.INDIVIDUAL)
            val individual = withTimeout(20000) { controller.state.first { !it.running } }
            assertNull(individual.error); assertTrue(individual.readyToSave); assertNull(controller.exportFile())
            assertEquals(BatchDownloadMode.INDIVIDUAL, individual.downloadMode)
            assertTrue(controller.beginFileExport("stale-request").isEmpty())
            val exported = controller.beginFileExport(individual.noticeId)
            assertEquals(listOf("first.bin", "电视.txt"), exported.map { it.name })
            assertContentEquals(first, exported[0].file.readBytes()); assertContentEquals(second, exported[1].file.readBytes())
            assertTrue(controller.state.value.saving)
            controller.cancel()
            assertTrue(exported.all { it.file.isFile })
            assertTrue(controller.beginFileExport(individual.noticeId).isEmpty())
            controller.fileSaved(individual.noticeId, "first.bin")
            controller.finishFileExport(individual.noticeId, "fixture partial save")
            val remaining = controller.beginFileExport(individual.noticeId)
            assertEquals(listOf("电视.txt"), remaining.map { it.name })
            controller.fileSaved(individual.noticeId, "电视.txt")
            controller.finishFileExport(individual.noticeId)
            assertEquals(2, controller.state.value.savedNames.size)
            assertTrue(controller.beginFileExport(individual.noticeId).isEmpty())

        }finally {controller.close();scope.cancel();runtime.close();process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS)}}
    }
    @Test fun interruptedUploadRequiresExplicitResumeAndKeepsAcknowledgedOffset()=runBlocking {
        val binary=File(requireNotNull(System.getProperty("airc.hostRuntime")));val port=ServerSocket(0).use {it.localPort}
        val state=temporary.newFolder("resume-state");val log=temporary.newFile("resume-runtime.log")
        val process=ProcessBuilder(binary.path,"serve","--no-mdns","--state-dir",state.path,"--port",port.toString()).redirectErrorStream(true).redirectOutput(log).start()
        val runtime=RuntimeConnection();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val controller=BatchTransferController(scope,runtime,temporary.newFolder("resume-cache"))
        val data=ByteArray(5*1024*1024){(it%241).toByte()};val opens=java.util.concurrent.atomic.AtomicInteger()
        try {
            withTimeout(10000){while(runCatching {runtime.connect("127.0.0.1",port)}.isFailure){check(process.isAlive){log.readText()};delay(50)}}
            controller.select(listOf(UploadSource("resume.bin",data.size.toLong()) {
                val attempt=opens.incrementAndGet()
                object:java.io.FilterInputStream(data.inputStream()) {
                    var count=0
                    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                        if(attempt==2 && count>=4*1024*1024)throw java.io.IOException("fixture transport interrupted")
                        val n=super.read(buffer,offset,length);if(n>0)count+=n;return n
                    }
                }
            }))
            controller.upload("inbox","")
            val interrupted=withTimeout(20000){controller.state.first {!it.running}}
            assertTrue(interrupted.paused);assertNotNull(interrupted.error);assertEquals(4L*1024*1024,interrupted.bytes)
            assertFalse(File(state,"files/resume.bin").exists())
            runtime.disconnect();runtime.connect("127.0.0.1",port);delay(100)
            assertEquals(2,opens.get());assertTrue(controller.state.value.paused)
            controller.resume()
            val done=withTimeout(20000){controller.state.first {!it.running}}
            assertNull(done.error);assertFalse(done.paused);assertEquals(3,opens.get())
            assertContentEquals(data,File(state,"files/resume.bin").readBytes())
        }finally {controller.close();scope.cancel();runtime.close();process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS)}}
    }

}
