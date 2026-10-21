package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** Deterministic provider and screen fixtures; no external model or Android target. */
class VisionProtocolTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun imageTasksReobserveAfterToolsClearImagesAndCanBeStopped()=runBlocking {
        val binary=File(requireNotNull(System.getProperty("airc.hostRuntime")));val port=ServerSocket(0).use {it.localPort}
        val state=temporary.newFolder("vision-state");val log=temporary.newFile("vision-runtime.log")
        val bin=temporary.newFolder("fixture-bin")
        File(bin,"screen.png").writeBytes(java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jGz0AAAAASUVORK5CYII="))
        File(bin,"screencap").apply {writeText("#!/bin/sh\ncat '${File(bin,"screen.png").path}'\n");setExecutable(true)}
        File(bin,"dumpsys").apply {writeText("#!/bin/sh\nprintf '%s\\n' 'mResumedActivity: ActivityRecord{ab u0 test.app/.Main}'\n");setExecutable(true)}
        val builder=ProcessBuilder(binary.path,"serve","--no-mdns","--state-dir",state.path,"--port",port.toString()).redirectErrorStream(true).redirectOutput(log)
        builder.environment()["PATH"]=bin.path+File.pathSeparator+builder.environment()["PATH"]
        val process=builder.start();val runtime=RuntimeConnection();val provider=MockWebServer().apply {start()}
        try {
            withTimeout(10000){while(runCatching {runtime.connect("127.0.0.1",port)}.isFailure){check(process.isAlive){log.readText()};delay(50)}}
            runtime.request("configure",arguments=buildJsonObject {put("baseUrl",provider.url("/v1").toString());put("apiKey","fixture-key");put("model","fixture-vision")})
            provider.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"observe","type":"function","function":{"name":"device_foreground","arguments":"{}"}}]}}]}"""))
            provider.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"已核对前台应用"}}]}"""))
            assertEquals("已核对前台应用",runtime.request("text",arguments=buildJsonObject {put("observeScreen",true)},text="看看当前界面"))
            val first=Json.parseToJsonElement(provider.takeRequest(3,TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            val second=Json.parseToJsonElement(provider.takeRequest(3,TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            fun imageCount(value:JsonObject)=value["messages"]!!.jsonArray.sumOf {message->(message.jsonObject["content"] as? JsonArray)?.count {it.jsonObject["type"]?.jsonPrimitive?.content=="image_url"} ?: 0}
            assertEquals(1,imageCount(first));assertEquals(1,imageCount(second))
            assertTrue(second["messages"]!!.jsonArray.any {it.jsonObject["role"]?.jsonPrimitive?.content=="tool"})
            provider.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"普通任务"}}]}"""))
            runtime.request("text",text="只读取文本状态")
            val ordinary=Json.parseToJsonElement(provider.takeRequest(3,TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            assertEquals(0,imageCount(ordinary))
            provider.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending=async(Dispatchers.Default) {runCatching {runtime.request("text",arguments=buildJsonObject {put("observeScreen",true)},text="等待任务")}}
            withContext(Dispatchers.IO){assertNotNull(provider.takeRequest(3,TimeUnit.SECONDS))}
            runtime.commandWriter()("cancel_text",buildJsonObject {})
            val stopped=withTimeout(3000){pending.await()};assertTrue(stopped.isFailure);assertTrue(stopped.exceptionOrNull()!!.message!!.contains("停止"))
        }finally {runtime.close();provider.shutdown();process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS)}}
    }
}
