package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.nio.charset.Charset
import kotlin.test.*

class FilePreviewClientTest {
    @Test fun textDecodingSupportsChineseBomAndRejectsBinary() {
        assertEquals("中文\n文本",decodeFilePreviewText("中文\n文本".toByteArray()).text)
        assertEquals("中文",decodeFilePreviewText(byteArrayOf(0xff.toByte(),0xfe.toByte())+"中文".toByteArray(Charsets.UTF_16LE)).text)
        assertEquals("中文",decodeFilePreviewText("中文".toByteArray(Charset.forName("GB18030"))).text)
        assertEquals("plain",decodeFilePreviewText(byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+"plain".toByteArray()).text)
        assertFails {decodeFilePreviewText(byteArrayOf(0,1,2,3))}
        assertNull(filePreviewKind(TargetFileEntry("escape.txt","symlink")))
        assertEquals(FilePreviewKind.TEXT, filePreviewKind(TargetFileEntry("alias", "file", symlink = true, targetName = "real.txt")))
        assertNull(filePreviewKind(TargetFileEntry("../escape.txt","file")))
        assertEquals(FilePreviewKind.TEXT,filePreviewKind(TargetFileEntry("FILE.YAML","file")))
    }

    @Test fun prefixReadsAreBoundedAndRequireExactRangeAndOriginalConnection():Unit = runBlocking {
        Fixture().use {f ->
            val source=f.client.open("inbox","sample.txt")
            assertContentEquals("hello".toByteArray(),source.read(5,true))
            assertEquals("bytes=0-4",f.ranges.receive())
            f.badRange=true
            assertFailsWith<IllegalStateException> {source.read(5,true)}
            f.ranges.receive()
            assertFailsWith<IllegalStateException> {source.read(5)}
            source.release()
            assertEquals(f.id,withTimeout(3000) {f.closed.receive()})
            f.runtime.disconnect()
            assertFailsWith<IllegalStateException> {source.read(5,true)}
        }
    }

    @Test fun cancelledDescriptorIsRevokedWithoutReadingData():Unit = runBlocking {
        Fixture(delayDescriptor=true).use {f ->
            val load=async {f.client.open("inbox","sample.txt")}
            val pending=withTimeout(3000) {f.requests.receive()}
            load.cancel()
            f.reply(pending)
            withTimeout(3000) {load.join()}
            assertEquals(f.id,withTimeout(3000) {f.closed.receive()})
            assertTrue(f.ranges.tryReceive().isFailure)
        }
    }

    @Test fun mismatchedTargetDescriptorIsRevoked():Unit = runBlocking {
        Fixture(wrongPath=true).use {f ->
            assertFailsWith<IllegalStateException> {f.client.open("inbox","sample.txt")}
            assertEquals(f.id,withTimeout(3000) {f.closed.receive()})
        }
    }

    private class Fixture(val delayDescriptor:Boolean=false,val wrongPath:Boolean=false):AutoCloseable {
        val id="12345678-1234-1234-1234-123456789abc"
        val requests=Channel<Pair<WebSocket,JsonObject>>(8)
        val closed=Channel<String>(8)
        val ranges=Channel<String>(8)
        @Volatile var badRange=false
        val runtime=RuntimeConnection()
        val client=FilePreviewClient(runtime)
        val server=MockWebServer()
        fun reply(request:Pair<WebSocket,JsonObject>) {
            val(socket,message)=request
            val result=buildJsonObject {put("id",id);put("root","inbox");put("path",if(wrongPath) "wrong.txt" else "sample.txt");put("sizeBytes",1000000);put("dataPath","/media/$id")}
            socket.send(buildJsonObject {put("id",message["id"]!!);put("protocol",1);put("ok",true);put("result",result.toString())}.toString())
        }
        init {
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse = when(request.path) {
                    "/status" -> MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"preview-fixture","tools":["files.preview","files.preview_close"]}""")
                    "/ws" -> MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
                        override fun onMessage(socket:WebSocket,text:String) {
                            val message=Json.parseToJsonElement(text).jsonObject
                            if(message["tool"]?.jsonPrimitive?.content=="files.preview_close") {
                                closed.trySend(message["arguments"]!!.jsonObject["id"]!!.jsonPrimitive.content)
                                socket.send(buildJsonObject {put("id",message["id"]!!);put("protocol",1);put("ok",true);put("result","{\"released\":true}")}.toString())
                            } else if(delayDescriptor) requests.trySend(socket to message) else reply(socket to message)
                        }
                    })
                    "/media/$id" -> {
                        ranges.trySend(request.getHeader("Range").orEmpty())
                        MockResponse().setResponseCode(206).setHeader("Content-Range",if(badRange) "bytes 1-5/1000000" else "bytes 0-4/1000000").setBody("hello")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            runBlocking {runtime.connect(server.hostName,server.port)}
        }
        override fun close() {client.close();runtime.close();server.shutdown()}
    }
}
