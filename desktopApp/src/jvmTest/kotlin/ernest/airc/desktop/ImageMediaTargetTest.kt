package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*
import java.nio.file.Files

class ImageMediaTargetTest {
    private val taskJson=Json {ignoreUnknownKeys=true}
    @Test fun liveMediaPrivateHttpRangesAndLocalImageDisplay(): Unit = runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned media fixture",!serial.isNullOrBlank())
        val adb=HostAdb();val target=ShellRuntimeTarget(serial!!)
        val runtime=RuntimeConnection();val transfer=RuntimeTransferClient(runtime)
        val directory=Files.createTempDirectory("airc-media-").toFile()
        val name="airc-media-${java.util.UUID.randomUUID()}.png"
        var resourceId:String?=null
        var player:String?=null
        var publicationId:String?=null
        var publicationPath:String?=null
        val fileMedia=FileMediaController(this,runtime)
        try {
            target.prepare()
            runtime.connect("127.0.0.1",checkNotNull(target.port))
            val image=java.awt.image.BufferedImage(640,360,java.awt.image.BufferedImage.TYPE_INT_RGB)
            for(y in 0 until 360)for(x in 0 until 640) image.setRGB(x,y,if(y<180)0x00ffff else 0xff00ff)
            val source=java.io.File(directory,name);javax.imageio.ImageIO.write(image,"png",source)
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){(it.toInt() and 255).toString(16).padStart(2,'0')}
            transfer.upload(name,source.length(),digest,source::inputStream)
            val args=buildJsonObject {put("root","inbox");put("path",name)}
            val players=Json.decodeFromString<TargetMediaPlayers>(runtime.request("tool","media.players",args))
            assertEquals("http",players.transport)
            assertTrue(players.packages.none {it.endsWith("frameworkpackagestubs")})
            assertTrue(players.packages.isNotEmpty(),"Target needs an HTTP image viewer")
            player=players.packages.first()
            val opened=Json.decodeFromString<TargetMediaOpen>(runtime.request("tool","media.open",buildJsonObject {put("root","inbox");put("path",name);put("package",player!!)}))
            assertTrue(opened.launched);assertFalse(opened.playbackVerified);resourceId=opened.resourceId
            val connection=java.net.URI("http://127.0.0.1:${checkNotNull(target.port)}/media/$resourceId").toURL().openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("Range","bytes=0-7")
            try {assertEquals(206,connection.responseCode);assertEquals("bytes 0-7/${source.length()}",connection.getHeaderField("Content-Range"));assertEquals("8",connection.getHeaderField("Content-Length"));assertContentEquals(byteArrayOf(-119,80,78,71,13,10,26,10),connection.inputStream.use {it.readBytes()})}finally{connection.disconnect()}
            // This target declares HTTP support but treats the URL as a local pathname.
            // Keep HTTP range verification separate from real local-file rendering.
            runtime.request("tool","media.release",buildJsonObject {put("id",resourceId!!)});resourceId=null
            fileMedia.inspect("inbox",name,true,true)
            val localState=withTimeout(5000){fileMedia.state.first {it.players!=null&&!it.busy}}
            val localPlayers=localState.players!!
            assertEquals("local-copy",localPlayers.transport);assertTrue(localPlayers.packages.isNotEmpty())
            player=localPlayers.packages.first()
            fileMedia.open(true)
            val prepared=withTimeout(15000){fileMedia.state.first {!it.busy}}
            assertNull(prepared.error)
            publicationId=checkNotNull(prepared.publicationId)
            val folder=".airc-media-$publicationId"
            publicationPath="$folder/$name"
            val copied=runtime.downloadFile(publicationPath!!)
            assertContentEquals(source.readBytes(),copied.second)
            var displayed=false
            repeat(10) {
                if(!displayed) {
                    delay(500)
                    val screen=javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(runtime.screenshot()))
                    var matches=0;var samples=0
                    for(y in 0 until screen.height step 16)for(x in 0 until screen.width step 16){
                        val color=screen.getRGB(x,y);val r=color shr 16 and 255;val g=color shr 8 and 255;val b=color and 255
                        if(b>210 && ((r<40&&g>210)||(r>210&&g<40)))matches++
                        samples++
                    }
                    displayed=matches>samples/5
                }
            }
            assertTrue(displayed,"Player launch alone is insufficient: fixture colors must appear on the target")
            fileMedia.release(true)
            withTimeout(5000){fileMedia.state.first {!it.busy}}
            assertNull(fileMedia.state.value.publicationId)
            assertFails{runtime.downloadFile(publicationPath!!)}
            assertContentEquals(source.readBytes(),runtime.downloadFile(name,"inbox").second)
            publicationId=null
            repeat(6) {
                var task=taskJson.decodeFromString<MediaPublicationTask>(runtime.request("tool","media.publish",args))
                withTimeout(10000) {while(task.phase !in listOf("completed","failed","cancelled")){delay(50);task=taskJson.decodeFromString<MediaPublicationTask>(runtime.request("tool","task.progress",buildJsonObject {put("id",task.id)}))}}
                assertEquals("completed",task.phase,task.error)
                val copy=checkNotNull(task.result);publicationId=copy.id
                assertEquals(digest,copy.sha256)
                assertContentEquals(source.readBytes(),runtime.downloadFile(copy.path).second)
                runtime.request("tool","media.release",buildJsonObject {put("id",copy.id)})
                publicationId=null
            }
        } finally {
            if(publicationId!=null)runCatching{runtime.request("tool","media.release",buildJsonObject {put("id",publicationId!!)})}
            if(resourceId!=null)runCatching {runtime.request("tool","media.release",buildJsonObject {put("id",resourceId!!)})}
            if(player!=null)runCatching {
                val window=adb.command("-s",serial,"shell","dumpsys window windows")
                if(window.lines().any {it.contains("mCurrentFocus")&&it.contains(player!!)})runtime.request("tool","remote.key",buildJsonObject {put("key","back")})
            }
            transfer.close();runtime.close()
            try {target.close()}finally {directory.deleteRecursively()}
        }
    }
}
