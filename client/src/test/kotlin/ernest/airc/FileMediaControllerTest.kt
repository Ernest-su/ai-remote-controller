package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class FileMediaControllerTest {
    @Test fun invalidAndDuplicatePlayerPackagesCannotLaunchMedia()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var packages="[\"fixture.viewer\"]";var opens=0
        val media=FileMediaController(scope){tool,_->
            if(tool=="media.players") """{"root":"inbox","path":"picture.png","mimeType":"image/png","transport":"http","packages":$packages}"""
            else {opens++;error("must not open")}
        }
        try {
            media.inspect("inbox","picture.png",true)
            assertEquals("fixture.viewer",media.state.value.selectedPackage)
            for(invalid in listOf("[\"fixture.viewer\",\"fixture.viewer\"]","[\"bad;command\"]","[\"fixture..viewer\"]")) {
                packages=invalid;media.inspect("inbox","picture.png",true)
                assertNotNull(media.state.value.error);assertNull(media.state.value.players)
                assertNull(media.state.value.selectedPackage);media.open(true)
            }
            assertEquals(0,opens)
        }finally{media.clear();scope.cancel()}
    }

    @Test fun recoveryRejectsNestedPathsDuplicateIdsAndOversizedCopies()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val id="11111111-1111-1111-1111-111111111111"
        fun copy(path:String=".airc-media-$id/picture.png",size:Long=10)=
            """{"id":"$id","root":"downloads","path":"$path","sizeBytes":$size,"sha256":"${"a".repeat(64)}"}"""
        var payload="[${copy()}]"
        val media=FileMediaController(scope){_,_->payload}
        try {
            media.recover(true)
            assertEquals(1,media.state.value.retainedCopies.size)
            for(invalid in listOf("[${copy(".airc-media-$id/nested/picture.png")}]", "[${copy()},${copy()}]", "[${copy(size=1024L*1024*1024*1024+1)}]")) {
                payload=invalid;media.recover(true)
                assertNotNull(media.state.value.error)
                assertEquals(1,media.state.value.retainedCopies.size)
            }
        }finally{media.clear();scope.cancel()}
    }

    @Test fun missingReleaseConfirmationRetainsResource()=verifyRejectedRelease("{}")
    @Test fun negativeReleaseConfirmationRetainsResource()=verifyRejectedRelease("""{"released":false}""")
    private fun verifyRejectedRelease(response:String):Unit=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val media=FileMediaController(scope){tool,_->when(tool){
            "media.players"->"""{"root":"inbox","path":"picture.png","mimeType":"image/png","transport":"http","packages":["fixture.viewer"]}"""
            "media.open"->"""{"root":"inbox","path":"picture.png","mimeType":"image/png","transport":"http","launched":true,"playbackVerified":false,"resourceId":"original","package":"fixture.viewer"}"""
            "media.release"->response
            else->error("Unexpected request")
        }}
        try {
            media.inspect("inbox","picture.png",true);media.open(true);media.release(true)
            assertEquals("original",media.state.value.resourceId)
            assertEquals("播放资源释放未确认，请核对目标状态",media.state.value.error)
            assertNull(media.state.value.message);assertFalse(media.state.value.busy)
        }finally{media.clear();scope.cancel()}
    }
    @Test fun foreignProgressDoesNotRedirectCancellationOrReleaseItsCopy():Unit=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val calls=mutableListOf<String>()
        val copy="11111111-1111-4111-8111-111111111111"
        val media=FileMediaController(scope){tool,args->
            calls+=tool
            when(tool) {
                "media.players"->"""{"root":"inbox","path":"picture.png","mimeType":"image/png","transport":"local-copy","packages":["fixture.viewer"],"sizeBytes":10}"""
                "media.publish"->"""{"id":"original","phase":"copying","bytesTransferred":0,"sizeBytes":10,"direction":"media_publish","root":"inbox","name":"picture.png","unit":"bytes"}"""
                "task.progress","task.cancel"->{
                    assertEquals("original",args["id"]!!.jsonPrimitive.content)
                    """{"id":"foreign","phase":"completed","bytesTransferred":10,"sizeBytes":10,"direction":"media_publish","root":"inbox","name":"picture.png","unit":"bytes","result":{"id":"$copy","root":"downloads","path":".airc-media-$copy/picture.png","sizeBytes":10,"sha256":"${"a".repeat(64)}"}}"""
                }
                else->error("Must not open or release foreign copy")
            }
        }
        try {
            media.inspect("inbox","picture.png",true,true);media.open(true)
            val state=withTimeout(3000){media.state.first{!it.busy}}
            assertEquals("播放副本任务对象不匹配",state.error)
            assertNull(state.publicationId)
            assertEquals(listOf("media.players","media.publish","task.progress","task.cancel"),calls)
        }finally{media.clear();scope.cancel()}
    }
    @Test fun publicationPollingAndCleanupRetainCapturedConnection():Unit=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var peer=0;val calls=mutableListOf<String>();var fallbackCalls=0
        val media=FileMediaController(scope, {
            val captured=peer
            val bound:suspend(String,JsonObject)->String={tool,_->
                check(captured==peer){"目标连接已改变"}
                calls+=tool
                when(tool){
                    "media.players"->"""{"root":"inbox","path":"picture.png","mimeType":"image/png","transport":"local-copy","packages":["fixture.viewer"],"sizeBytes":10}"""
                    "media.publish"->{peer++;"""{"id":"fixture-task","phase":"copying","bytesTransferred":0,"sizeBytes":10,"direction":"media_publish","root":"inbox","name":"picture.png","unit":"bytes"}"""}
                    else->error("unexpected old-peer command")
                }
            }
            bound
        }, {_,_->fallbackCalls++;error("unbound request")})
        try {
            media.inspect("inbox","picture.png",true,true)
            media.open(true)
            withTimeout(3000){media.state.first{!it.busy}}
            assertEquals("目标连接已改变",media.state.value.error)
            assertEquals(listOf("media.players","media.publish"),calls)
            assertEquals(0,fallbackCalls)
        }finally{media.clear();scope.cancel()}
    }
    @Test fun playersAreQueriedBeforeExplicitOpenAndResourceRelease()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val calls=mutableListOf<String>()
        val media=FileMediaController(scope){tool,args ->
            calls+=tool
            when(tool) {
                "media.players" -> """{"root":"inbox","path":"picture.png","mimeType":"image/png","transport":"http","packages":["com.test.viewer"]}"""
                "media.open" -> {assertEquals("com.test.viewer",args["package"]!!.jsonPrimitive.content);"""{"root":"inbox","path":"picture.png","mimeType":"image/png","transport":"http","launched":true,"playbackVerified":false,"resourceId":"fixture","package":"com.test.viewer"}"""}
                else -> """{"released":true}"""
            }
        }
        try {
            media.inspect("inbox","picture.png",false);assertTrue(calls.isEmpty())
            media.inspect("inbox","picture.png",true);assertEquals(listOf("media.players"),calls)
            media.choose("unknown");assertEquals("com.test.viewer",media.state.value.selectedPackage)
            media.open(true);assertEquals("fixture",media.state.value.resourceId)
            assertTrue(media.state.value.message!!.contains("实际播放状态"))
            media.release(true);assertNull(media.state.value.resourceId)
            assertEquals(listOf("media.players","media.open","media.release"),calls)
        } finally {scope.cancel()}
    }
    @Test fun localCopyPreparesThenOpensAndFailureReleasesCopy()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val calls=mutableListOf<String>()
        val id="11111111-1111-4111-8111-111111111111"
        val media=FileMediaController(scope){tool,args ->
            calls+=tool
            when(tool){
                "media.players"->{assertEquals("local-copy",args["transport"]!!.jsonPrimitive.content);"""{"root":"inbox","path":"picture.png","mimeType":"image/png","transport":"local-copy","packages":["com.test.viewer"],"sizeBytes":10}"""}
                "media.publish"-> """{"id":"task","phase":"completed","bytesTransferred":10,"sizeBytes":10,"direction":"media_publish","root":"inbox","name":"picture.png","unit":"bytes","result":{"id":"$id","root":"downloads","path":".airc-media-$id/picture.png","sizeBytes":10,"sha256":"${"a".repeat(64)}"}}"""
                "media.open"->{assertEquals("downloads",args["root"]!!.jsonPrimitive.content);error("fixture player refused")}
                else->"""{"released":true}"""
            }
        }
        try{
            media.inspect("inbox","picture.png",true,true);media.open(true)
            assertEquals(listOf("media.players","media.publish","media.open","media.release"),calls)
            assertEquals("fixture player refused",media.state.value.error)
            assertFalse(media.state.value.busy);assertFalse(media.state.value.preparingCopy)
        }finally{scope.cancel()}
    }
    @Test fun retainedCopiesAreLoadedExplicitlyAndReleasedIndividually()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val calls=mutableListOf<String>()
        val id="11111111-1111-4111-8111-111111111111"
        val media=FileMediaController(scope){tool,args->
            calls+=tool
            if(tool=="media.publications") """[{"id":"$id","root":"downloads","path":".airc-media-$id/picture.png","sizeBytes":10,"sha256":"${"a".repeat(64)}"}]"""
            else {assertEquals(id,args["id"]!!.jsonPrimitive.content);"""{"released":true}"""}
        }
        try{
            assertTrue(calls.isEmpty());media.recover(true)
            assertEquals(1,media.state.value.retainedCopies.size)
            media.releaseCopy("unknown",true);assertEquals(1,calls.size)
            media.releaseCopy(id,true);assertTrue(media.state.value.retainedCopies.isEmpty())
            assertEquals(listOf("media.publications","media.release"),calls)
        }finally{scope.cancel()}
    }
    @Test fun changedTargetCannotRestoreOldPlayerChoices()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val pending=CompletableDeferred<String>()
        val media=FileMediaController(scope){_,_->withContext(NonCancellable){pending.await()}}
        try {
            media.inspect("inbox","old.png",true);media.clear()
            pending.complete("""{"root":"inbox","path":"old.png","mimeType":"image/png","transport":"http","packages":["com.test.viewer"]}""")
            yield();assertNull(media.state.value.players);assertFalse(media.state.value.busy)
        } finally {scope.cancel()}
    }
}
