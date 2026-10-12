package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class MediaPublicationIdentityTest {
    private val id="11111111-1111-4111-8111-111111111111"
    private val copy=MediaPublication(id,"downloads",".airc-media-$id/picture.png",10,"a".repeat(64))
    private val task=MediaPublicationTask("original","completed",10,10,result=copy,
        direction="media_publish",root="inbox",name="picture.png",unit="bytes")
    @Test fun rejectsDifferentTaskType()=verify(task.copy(direction="download"),"播放副本任务对象不匹配")
    @Test fun rejectsDifferentSourceRoot()=verify(task.copy(root="downloads"),"播放副本任务对象不匹配")
    @Test fun rejectsDifferentSourcePath()=verify(task.copy(name="other.png"),"播放副本任务对象不匹配")
    @Test fun rejectsProgressBeyondSize()=verify(task.copy(bytesTransferred=11),"播放副本进度无效")
    @Test fun rejectsCopyWithDifferentSize()=verify(task.copy(result=copy.copy(sizeBytes=9)),"播放副本描述无效")
    @Test fun rejectsCopyWithDifferentFilename()=verify(task.copy(result=copy.copy(path=".airc-media-$id/other.png")),"播放副本描述无效")

    private fun verify(response:MediaPublicationTask,error:String):Unit=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val calls=mutableListOf<String>()
        val media=FileMediaController(scope){tool,args->
            calls+=tool
            when(tool) {
                "media.players"->Json.encodeToString(TargetMediaPlayers("inbox","picture.png","image/png","local-copy",listOf("fixture.viewer"),10))
                "media.publish"->Json.encodeToString(response)
                "task.cancel"->{
                    assertEquals("original",args["id"]!!.jsonPrimitive.content)
                    Json.encodeToString(task.copy(phase="cancelled",bytesTransferred=0,result=null))
                }
                else->error("Must not open or release an unverified copy")
            }
        }
        try {
            media.inspect("inbox","picture.png",true,true);media.open(true)
            assertEquals(error,media.state.value.error)
            assertNull(media.state.value.publicationId);assertFalse(media.state.value.busy)
            assertEquals(listOf("media.players","media.publish","task.cancel"),calls)
        }finally{media.clear();scope.cancel()}
    }
}
