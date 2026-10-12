package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import kotlin.test.*

class CacheTaskIdentityTest {
    @Test fun rejectsDifferentTaskInProgressResponse() = verify(false)
    @Test fun rejectsWrongTaskTypeFromApproval() = verify(true)

    private fun verify(wrongInitialType: Boolean): Unit = runBlocking {
        val requestedIds = mutableListOf<String>()
        val app = TargetAppInfo("fixture.app",0,10001,1,codePath="/data/app/fixture",lastUpdateTime="fixture",system=false)
        val proposal = AppCacheProposal("proposal","app_cache_clear",app,true,true,"package_manager_cache_only")
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object: WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                val message = Json.parseToJsonElement(text).jsonObject
                val tool = message["tool"]?.jsonPrimitive?.content
                val result = when {
                    tool == "apps.clear_cache" -> Json.encodeToString(proposal)
                    message["type"]?.jsonPrimitive?.content == "approve_cache" -> Json.encodeToString(TransferProgress("original",if(wrongInitialType) "delete" else "app_cache_clear","fixture.app",0,0,"committing",dataPath="/transfers/original/data",root="apps",unit="items"))
                    tool == "task.progress" -> {
                        synchronized(requestedIds) { requestedIds += message.getValue("arguments").jsonObject.getValue("id").jsonPrimitive.content }
                        Json.encodeToString(TransferProgress("foreign","app_cache_clear","fixture.app",0,1,"completed",dataPath="/transfers/foreign/data",root="apps",unit="items",result=buildJsonObject { put("package","fixture.app");put("userId",0);put("cacheOnly",true);put("completed",true) }))
                    }
                    else -> error("Unexpected fixture request")
                }
                socket.send(buildJsonObject { put("id",message.getValue("id"));put("protocol",1);put("ok",true);put("result",result) }.toString())
            }
        }))
        server.start()
        val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val controller = AppCacheController(scope,runtime)
        var completed = false
        try {
            runtime.connect(server.hostName,server.port)
            controller.prepare("fixture.app",true) { completed=true }
            withTimeout(3000) { controller.state.first { it.proposal!=null } }
            controller.confirm(true)
            val state = withTimeout(3000) { controller.state.first { !it.busy } }
            assertEquals("缓存清理任务对象不匹配",state.error)
            assertNull(state.message);assertFalse(completed)
            assertEquals(if(wrongInitialType) emptyList() else listOf("original"),synchronized(requestedIds) { requestedIds.toList() })
        } finally { controller.clear();runtime.close();scope.cancel();server.shutdown() }
    }
}
