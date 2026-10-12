package ernest.airc.desktop

import ernest.airc.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Real Android file tools, deterministic host model; no changes to the main service. */
class ModelFileToolsTargetTest {
    @Test fun modelCreatesInspectsAndOnlyProposesDeletion():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned model/file fixture",!serial.isNullOrBlank())
        val target=ShellRuntimeTarget(serial!!);val adb=HostAdb();val runtime=RuntimeConnection()
        val api=MockWebServer();val calls=AtomicInteger();val failures=CopyOnWriteArrayList<String>()
        val tools=listOf("files.mkdir","files.stat","files.delete");val path="model-directory"
        var reversePort:Int?=null
        api.dispatcher=object:Dispatcher(){
            override fun dispatch(request:RecordedRequest):MockResponse=try {
                assertEquals("/v1/chat/completions",request.path)
                assertEquals("Bearer target-fixture-key",request.getHeader("Authorization"))
                val body=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals("target-fixture-model",body["model"]!!.jsonPrimitive.content)
                val names=body["tools"]!!.jsonArray.map{it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content}
                assertTrue(tools.all{it.replace('.','_') in names});assertFalse("approve" in names)
                val messages=body["messages"]!!.jsonArray
                val results=messages.filter{it.jsonObject["role"]!!.jsonPrimitive.content=="tool"}
                results.forEachIndexed{index,item->
                    val feedback=Json.parseToJsonElement(item.jsonObject["content"]!!.jsonPrimitive.content).jsonObject
                    assertTrue(feedback["ok"]!!.jsonPrimitive.boolean)
                    val output=Json.parseToJsonElement(feedback["output"]!!.jsonPrimitive.content).jsonObject
                    assertEquals("inbox",output["root"]!!.jsonPrimitive.content);assertEquals(path,output["path"]!!.jsonPrimitive.content)
                    when(index){
                        0->assertTrue(output["created"]!!.jsonPrimitive.boolean)
                        1->assertEquals("directory",output["kind"]!!.jsonPrimitive.content)
                        2->{assertEquals("delete",output["kind"]!!.jsonPrimitive.content);assertTrue(output["approvalRequired"]!!.jsonPrimitive.boolean)}
                        else->error("Unexpected extra tool result")
                    }
                }
                val index=results.size
                val message=if(index==tools.size)buildJsonObject{put("role","assistant");put("content","deletion awaits user approval")}
                else buildJsonObject{
                    put("role","assistant");put("content",JsonNull)
                    putJsonArray("tool_calls"){add(buildJsonObject{
                        put("id","file-$index");put("type","function")
                        putJsonObject("function"){
                            put("name",tools[index].replace('.','_'))
                            put("arguments",buildJsonObject{put("root","inbox");put("path",path);if(index==2)put("recursive",true)}.toString())
                        }
                    })}
                }
                calls.incrementAndGet()
                MockResponse().setHeader("Content-Type","application/json").setBody(buildJsonObject{
                    putJsonArray("choices"){add(buildJsonObject{put("message",message)})}
                }.toString())
            }catch(error:Throwable){failures+=error.toString();MockResponse().setResponseCode(500)}
        }
        try {
            api.start();target.prepare()
            reversePort=adb.command("-s",serial,"reverse","tcp:0","tcp:${api.port}").trim().toInt()
            runtime.connect("127.0.0.1",target.port!!)
            runtime.request("configure",arguments=buildJsonObject{
                put("baseUrl","http://127.0.0.1:$reversePort/v1");put("apiKey","target-fixture-key");put("model","target-fixture-model")
            })
            val progress=CopyOnWriteArrayList<RuntimeProgress>()
            assertEquals("deletion awaits user approval",withTimeout(30000){runtime.request("text",text="Create and inspect the inbox directory, then prepare its deletion. Do not approve it.",onProgress={progress+=it})})
            assertEquals(4,calls.get());assertTrue(failures.isEmpty(),failures.toString())
            assertEquals(tools,progress.filter{it.phase=="tool"}.map{it.tool})
            assertTrue(progress.filter{it.phase=="tool"}.all{it.text.isEmpty()})
            val args=buildJsonObject{put("root","inbox");put("path",path)}
            suspend fun verifyDirectory(){
                val stat=Json.parseToJsonElement(runtime.request("tool","files.stat",args)).jsonObject
                assertEquals("directory",stat["kind"]!!.jsonPrimitive.content)
                assertTrue(target.inboxEntries().lineSequence().any{it.endsWith("/$path")})
            }
            verifyDirectory()
            val pending=Json.parseToJsonElement(runtime.request("tool","files.pending")).jsonArray.single().jsonObject
            assertEquals(path,pending["path"]!!.jsonPrimitive.content);assertTrue(pending["approvalRequired"]!!.jsonPrimitive.boolean)
            val rejected=Json.parseToJsonElement(runtime.request("approve",arguments=buildJsonObject{put("id",pending["id"]!!.jsonPrimitive.content);put("accepted",false)})).jsonObject
            assertTrue(rejected["rejected"]!!.jsonPrimitive.boolean)
            verifyDirectory()
            assertTrue(Json.parseToJsonElement(runtime.request("tool","files.pending")).jsonArray.isEmpty())
        }finally{
            runtime.close()
            try{reversePort?.let{adb.command("-s",serial,"reverse","--remove","tcp:$it")}}
            finally{try{target.close()}finally{api.shutdown()}}
        }
    }
}
