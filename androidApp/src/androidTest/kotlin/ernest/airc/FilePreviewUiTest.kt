package ernest.airc

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/** Loopback fixtures: media decoding and lifecycle on the controller only. */
class FilePreviewUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun sampledImagesKeepOriginalPixelsAndBoundDecodedSize() {
        val bitmap=Bitmap.createBitmap(1024,768,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        val bytes=ByteArrayOutputStream().use {out -> bitmap.compress(Bitmap.CompressFormat.PNG,100,out);out.toByteArray()}
        bitmap.recycle()
        val decoded=checkNotNull(decodePreviewBitmap(bytes,160))
        assertTrue(decoded.width<=160 && decoded.height<=160)
        assertEquals(android.graphics.Color.GREEN,decoded.asAndroidBitmap().getPixel(0,0))
    }

    @Test fun textPreviewReadsOnlyPrefixAndReleasesOnClose() {
        val bytes="中文内容\n".repeat(30000).toByteArray()
        Fixture("notes.txt",bytes).use {f ->
            var show by mutableStateOf(true)
            compose.setContent {RemoteTheme {Column {
                TextButton({show=false}) {Text("关闭测试预览")}
                if(show) AndroidFilePreview(TargetFileEntry(f.name,"file",bytes.size.toLong()),f.client,"inbox",f.name)
            }}}
            compose.waitUntil(15000) {compose.onAllNodesWithTag("file-text-preview").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("UTF-8 · 仅显示前 256 KiB，完整内容请下载查看").assertExists()
            assertEquals("bytes=0-262143",f.lastRange)
            compose.onNodeWithText("关闭测试预览").performClick()
            compose.waitUntil(5000) {f.released.get()==1}
        }
    }

    @Test fun controllerAudioPausesInBackgroundAndClosingReleasesStream() {
        Fixture("sample.wav",wave()).use {f ->
            var show by mutableStateOf(true)
            compose.setContent {RemoteTheme {Column {
                TextButton({show=false}) {Text("关闭测试预览")}
                if(show) AndroidFilePreview(TargetFileEntry(f.name,"file",f.bytes.size.toLong()),f.client,"inbox",f.name)
            }}}
            compose.waitUntil(20000) {compose.onAllNodesWithTag("file-player-toggle").fetchSemanticsNodes().any {!it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}}
            compose.onNodeWithTag("file-player-toggle").assertIsEnabled().performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithText("暂停").fetchSemanticsNodes().isNotEmpty()}
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("file-player-toggle").assertTextEquals("播放")
            compose.onNodeWithText("关闭测试预览").performClick()
            compose.waitUntil(5000) {f.released.get()==1}
            compose.onNodeWithTag("file-player-toggle").assertDoesNotExist()
        }
    }

    private fun wave():ByteArray {
        val pcm=ByteArray(8000*2*30)
        return ByteBuffer.allocate(44+pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16)
            putShort(1);putShort(1);putInt(8000);putInt(16000);putShort(2);putShort(16)
            put("data".toByteArray());putInt(pcm.size);put(pcm)
        }.array()
    }

    private class Fixture(val name:String,val bytes:ByteArray):AutoCloseable {
        val id="12345678-1234-1234-1234-123456789abc"
        val server=MockWebServer();val runtime=RuntimeConnection();val client=FilePreviewClient(runtime)
        val released=AtomicInteger()
        @Volatile var lastRange:String?=null
        init {
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse = when(request.path) {
                    "/status" -> MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"preview-ui","tools":["files.preview","files.preview_close"]}""")
                    "/ws" -> MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
                        override fun onMessage(webSocket:WebSocket,text:String) {
                            val msg=Json.parseToJsonElement(text).jsonObject
                            val result=if(msg["tool"]?.jsonPrimitive?.content=="files.preview_close") {
                                released.incrementAndGet();"{\"released\":true}"
                            } else buildJsonObject {put("id",id);put("root","inbox");put("path",name);put("sizeBytes",bytes.size);put("dataPath","/media/$id")}.toString()
                            webSocket.send(buildJsonObject {put("id",msg["id"]!!);put("protocol",1);put("ok",true);put("result",result)}.toString())
                        }
                    })
                    "/media/$id" -> {
                        val range=request.getHeader("Range");lastRange=range
                        val parts=range?.removePrefix("bytes=")?.split('-')
                        val start=parts?.getOrNull(0)?.toIntOrNull() ?: 0
                        val end=minOf(parts?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex,bytes.lastIndex)
                        MockResponse().setResponseCode(if(range!=null)206 else 200).apply {
                            if(range!=null)setHeader("Content-Range","bytes $start-$end/${bytes.size}")
                            setHeader("Accept-Ranges","bytes");setHeader("Content-Type","application/octet-stream")
                            setBody(Buffer().write(bytes,start,end-start+1))
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start();runBlocking {runtime.connect(server.hostName,server.port)}
        }
        override fun close() {runtime.close();client.close();server.shutdown()}
    }
}
