package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*
import java.nio.file.Files
import java.io.File

class StandardMediaTargetTest {
    private val fixturePackage="ernest.airc.mediafixture"
    private val json=Json {ignoreUnknownKeys=true}
    @Test fun liveStandardPlayerDecodesAudioVideoAndReportsAdvancingPlayback():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for disposable standard media fixture",!serial.isNullOrBlank())
        val apk=File(checkNotNull(System.getProperty("airc.mediaFixtureApk")));assertTrue(apk.isFile)
        val adb=HostAdb()
        check(adb.command("-s",serial!!,"shell","pm list packages --user all $fixturePackage").lines().none{it=="package:$fixturePackage"}){"Refusing to replace an existing fixture package"}
        val target=ShellRuntimeTarget(serial);val runtime=RuntimeConnection();val transfer=RuntimeTransferClient(runtime)
        val directory=Files.createTempDirectory("airc-standard-media-").toFile()
        val name="airc-audio-${java.util.UUID.randomUUID()}.wav"
        val videoName="airc-video-${java.util.UUID.randomUUID()}.mp4"
        val source=File(directory,name);silentWave(source)
        val media=FileMediaController(this,runtime)
        val control=MediaControlController(this,runtime)
        var installed=false;var copy:String?=null
        suspend fun releaseAndCheck() {
            val id=checkNotNull(copy)
            val before=json.decodeFromString<List<MediaPublication>>(runtime.request("tool","media.publications"))
            val publication=before.single{it.id==id}
            media.release(true)
            val released=withTimeout(5000){media.state.first{!it.busy}}
            assertNull(released.error)
            assertNull(released.publicationId)
            assertNull(released.resourceId)
            assertTrue(checkNotNull(released.message).contains("已释放"))
            val after=json.decodeFromString<List<MediaPublication>>(runtime.request("tool","media.publications"))
            assertTrue(after.none{it.id==id},"Released copy must leave target publication inventory")
            assertFails { runtime.request("tool","files.stat",buildJsonObject{put("root",publication.root);put("path",publication.path)}) }
            copy=null
        }
        try {
            target.prepare()
            adb.command("-s",serial,"install",apk.absolutePath);installed=true
            adb.command("-s",serial,"shell","pm grant $fixturePackage android.permission.READ_EXTERNAL_STORAGE")
            runtime.connect("127.0.0.1",checkNotNull(target.port))
            transfer.upload(name,source.length(),mediaDigest(source),source::inputStream)
            media.inspect("inbox",name,true,true)
            val choices=withTimeout(5000){media.state.first{!it.busy&&it.players!=null}}
            assertTrue(fixturePackage in choices.players!!.packages)
            media.choose(fixturePackage);media.open(true)
            val opened=withTimeout(15000){media.state.first{!it.busy}}
            assertNull(opened.error);copy=checkNotNull(opened.publicationId)
            suspend fun own()=json.decodeFromString<MediaSessions>(runtime.request("tool","media.status")).sessions.firstOrNull{it.packageName==fixturePackage}
            val playing=withTimeout(10000){var value=own();while(value?.state!=3 || value.active!=true){delay(200);value=own()};value!!}
            val position=checkNotNull(playing.positionMs)
            delay(1000)
            assertTrue((own()?.positionMs ?: -1)>position,"Real decoded playback position must advance")
            runtime.request("tool","remote.key",buildJsonObject{put("key","play_pause")})
            withTimeout(15000){while(true){val state=own();if(state?.state==2)break;delay(200)}}
            val standard=json.decodeFromString<MediaSessions>(runtime.request("tool","media.status"));assertTrue(standard.seekAvailable)
            val ownPaused=standard.sessions.single{it.packageName==fixturePackage};assertEquals(20000L,ownPaused.durationMs)
            control.refresh(true);withTimeout(5000){control.state.first{!it.busy}}
            control.choose(checkNotNull(ownPaused.selection()));assertTrue(control.seek(7000,true))
            val seeked=withTimeout(15000){control.state.first{!it.busy}};assertNull(seeked.error);assertTrue(seeked.message!!.contains("播放器报告位置"))
            assertTrue(kotlin.math.abs(checkNotNull(own()?.positionMs)-7000)<=500)
            assertFails {runtime.request("tool","media.seek",buildJsonObject{put("package",fixturePackage);put("positionMs",25000)})}

            val custom="ernest.airc.fixture.action.once"
            assertTrue(standard.customActionAvailable)
            assertEquals(listOf(MediaCustomAction(custom,"Fixture one-shot action")),ownPaused.customActions)
            assertFails {runtime.request("tool","media.custom_action",buildJsonObject{put("package",fixturePackage);put("action","undeclared")})}
            assertTrue(control.customAction(custom,true))
            val acted=withTimeout(15000){control.state.first{!it.busy}};assertNull(acted.error)
            withTimeout(15000){while(own()?.description!="AIRC custom action applied")delay(200)}
            assertTrue(checkNotNull(own()).customActions.isEmpty())
            assertFails {runtime.request("tool","media.custom_action",buildJsonObject{put("package",fixturePackage);put("action",custom)})}

            releaseAndCheck()
            assertContentEquals(source.readBytes(),runtime.downloadFile(name,"inbox").second)
            adb.command("-s",serial,"shell","am start -W -n $fixturePackage/.ColorActivity")
            delay(500)
            adb.command("-s",serial,"shell","test ! -e '${target.inboxPath}/$videoName' && screenrecord --size 320x240 --bit-rate 1000000 --time-limit 8 '${target.inboxPath}/$videoName'")
            media.inspect("inbox",videoName,true,true)
            withTimeout(5000){media.state.first{!it.busy&&it.players?.path==videoName}}
            media.choose(fixturePackage);media.open(true)
            val video=withTimeout(15000){media.state.first{!it.busy}}
            assertNull(video.error);copy=checkNotNull(video.publicationId)
            delay(1000)
            withTimeout(10000){while(json.decodeFromString<MediaSessions>(runtime.request("tool","media.status")).sessions.none{it.packageName==fixturePackage&&it.active&&it.state==3})delay(200)}
            var displayed=false
            repeat(6){if(!displayed){delay(300);val pixels=runtime.screenshot();val screen=javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(pixels));var matches=0;var samples=0
                for(y in 0 until screen.height step 16)for(x in 0 until screen.width step 16){val color=screen.getRGB(x,y);val r=color shr 16 and 255;val g=color shr 8 and 255;val b=color and 255;if(b>190&&((r<60&&g>190)||(r>190&&g<60)))matches++;samples++}
                displayed=matches>samples/5
            }}
            assertTrue(displayed,"Decoded H.264 fixture colors must appear on the target")
            runtime.request("tool","remote.key",buildJsonObject{put("key","play_pause")})
            withTimeout(15000){while(true){val state=own();if(state?.state==2)break;delay(200)}}
            val videoPaused=checkNotNull(own());assertTrue(checkNotNull(videoPaused.durationMs)>2000)
            control.refresh(true);withTimeout(5000){control.state.first{!it.busy}};control.choose(checkNotNull(videoPaused.selection()))
            assertTrue(control.seek(2000,true));val videoSeek=withTimeout(15000){control.state.first{!it.busy}};assertNull(videoSeek.error)
            withTimeout(15000){while(own()?.let{it.state==2&&it.positionMs!=null&&kotlin.math.abs(it.positionMs!!-2000)<=500}!=true)delay(200)}
            releaseAndCheck()

        } finally {
            copy?.let{id->runCatching{runtime.request("tool","media.release",buildJsonObject{put("id",id)})}}
            try {
                if(installed)check(adb.command("-s",serial,"uninstall",fixturePackage).contains("Success")){"Fixture uninstall failed"}
            } finally {
                control.clear();transfer.close();runtime.close()
                try{target.close()}finally{directory.deleteRecursively()}
            }
        }
    }
}
