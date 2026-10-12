package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*
import java.nio.file.Files

class AudioVideoTargetTest {
    @Test fun liveVendorWavProbeRequiresActualPlayingEvidence():Unit=runBlocking {
        assumeTrue("Explicit known-failing vendor playback probe",System.getProperty("airc.vendorMediaProbe")=="true")
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned silent WAV playback",!serial.isNullOrBlank())
        val adb=HostAdb();val target=ShellRuntimeTarget(serial!!)
        val runtime=RuntimeConnection();val transfer=RuntimeTransferClient(runtime)
        val directory=Files.createTempDirectory("airc-audio-").toFile()
        val name="airc-audio-${java.util.UUID.randomUUID()}.wav"
        val source=java.io.File(directory,name)
        silentWave(source)
        val digest=mediaDigest(source)
        val media=FileMediaController(this,runtime)
        var copyId:String?=null;var player:String?=null
        try {
            target.prepare()
            runtime.connect("127.0.0.1",checkNotNull(target.port))
            transfer.upload(name,source.length(),digest,source::inputStream)
            media.inspect("inbox",name,true,true)
            val selected=withTimeout(5000){media.state.first{!it.busy&&it.players!=null}}
            assertTrue(selected.players!!.packages.isNotEmpty());player=selected.selectedPackage
            media.open(true)
            val opened=withTimeout(15000){media.state.first{!it.busy}}
            assertNull(opened.error);copyId=checkNotNull(opened.publicationId)
            delay(3000)
            val sessions=Json.decodeFromString<MediaSessions>(runtime.request("tool","media.status"))
            val own=sessions.sessions.filter{it.packageName==player}
            println("Owned WAV session evidence: "+own.map{Triple(it.active,it.state,it.positionMs)})
            assertTrue(own.any{it.active&&it.state==3},"Need real PLAYING MediaSession for the selected player")
            media.release(true);withTimeout(5000){media.state.first{!it.busy}};copyId=null
            assertContentEquals(source.readBytes(),runtime.downloadFile(name,"inbox").second)
        } finally {
            copyId?.let{id->runCatching{runtime.request("tool","media.release",buildJsonObject{put("id",id)})}}
            if(player!=null)runCatching{val window=adb.command("-s",serial,"shell","dumpsys window windows");if(window.lines().any{it.contains("mCurrentFocus")&&it.contains(player!!)})runtime.request("tool","remote.key",buildJsonObject{put("key","back")})}
            transfer.close();runtime.close()
            try{target.close()}
            finally{directory.deleteRecursively()}
        }
    }
}
