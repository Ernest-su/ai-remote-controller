package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import kotlin.test.*

class AppTaskIdentityTest {
    @Test fun uninstallRejectsForeignProgress() = verify(false,false)
    @Test fun uninstallRejectsInitialType() = verify(false,true)
    @Test fun installRejectsForeignProgress() = verify(true,false)
    @Test fun installRejectsInitialType() = verify(true,true)

    @Test fun uninstallRejectsInvalidCompletedResult() = verify(false,false,true)

    @Test fun installRejectsDifferentUser() = verify(true,false,true)
    @Test fun installRejectsDifferentDigest() = verify(true,false,true,wrongDigest=true)
    @Test fun installAcceptsMatchingUserAndDigest() = verify(true,false,true,validInstall=true)

    private fun verify(install: Boolean, wrongInitialType: Boolean, wrongResult: Boolean = false, wrongDigest: Boolean = false, validInstall: Boolean = false): Unit = runBlocking {
        val requestedIds = mutableListOf<String>()
        val app = TargetAppInfo("fixture.app",0,10001,1,codePath="/data/app/fixture",lastUpdateTime="fixture",system=false)
        val apk=TargetApkInfo("inbox","fixture.apk","fixture.app",1,sizeBytes=7,sha256="a".repeat(64),signatures=emptyList(),signatureVerified=false)
        val proposal=if(install) Json.encodeToString(listOf(AppInstallProposal("proposal","app_install",apk,userId=0,update=false,approvalRequired=true,codeMayAffectAllUsers=true))) else Json.encodeToString(listOf(AppUninstallProposal("proposal","app_uninstall",app,true,true)))
        val direction=if(install) "app_install" else "app_uninstall"
        val unit=if(install) "bytes" else "items"
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object: WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                val message = Json.parseToJsonElement(text).jsonObject
                val tool = message["tool"]?.jsonPrimitive?.content
                val result = when {
                    tool == (if(install) "apps.install_pending" else "apps.pending") -> proposal
                    message["type"]?.jsonPrimitive?.content == (if(install) "approve_install" else "approve_app") -> Json.encodeToString(TransferProgress("original",if(wrongInitialType) "delete" else direction,"fixture.app",0,0,"committing",dataPath="/transfers/original/data",root="apps",unit=unit))
                    tool == "apps.info" -> Json.encodeToString(app)
                    tool == "task.progress" -> {
                        synchronized(requestedIds) { requestedIds += message.getValue("arguments").jsonObject.getValue("id").jsonPrimitive.content }
                        Json.encodeToString(TransferProgress(if(wrongResult) "original" else "foreign",direction,"fixture.app",0,1,"completed",dataPath="/transfers/foreign/data",root="apps",unit=unit,result=buildJsonObject { put("package","fixture.app");put("userId",0);if(install) { put("installed",true);put("versionCode",1);put("userId",if(validInstall || wrongDigest) 0 else 10);put("sha256",if(wrongDigest) "b".repeat(64) else apk.sha256) } else { put("cacheOnly",true);put("completed",true) } }))
                    }
                    else -> error("Unexpected fixture request")
                }
                socket.send(buildJsonObject { put("id",message.getValue("id"));put("protocol",1);put("ok",true);put("result",result) }.toString())
            }
        }))
        server.start()
        val runtime = RuntimeConnection()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val management=AppManagementController(scope,runtime)
        val installation=AppInstallationController(scope,runtime)
        try {
            runtime.connect(server.hostName,server.port)
            if(install) {
                installation.pending(true)
                withTimeout(3000) { installation.state.first { it.proposal!=null } }
                installation.confirm(true)
                val state=withTimeout(3000) { installation.state.first { !it.busy } }
                if(validInstall) { assertNull(state.error);assertEquals("completed",state.phase);assertNotNull(state.message) }
                else { assertEquals(if(wrongResult) "安装结果对象不匹配，请查询应用信息" else "安装任务对象不匹配",state.error);assertNull(state.message) }
            } else {
                management.pending(true)
                withTimeout(3000) { management.state.first { it.proposal!=null } }
                management.confirm(true)
                val state=withTimeout(3000) { management.state.first { !it.busy } }
                assertEquals(if(wrongResult) "卸载结果对象不匹配" else "卸载任务对象不匹配",state.error);assertNull(state.message)
            }
            assertEquals(if(wrongInitialType) emptyList() else listOf("original"),synchronized(requestedIds) { requestedIds.toList() })
        } finally { management.clear();installation.clear();runtime.close();scope.cancel();server.shutdown() }
    }
}
