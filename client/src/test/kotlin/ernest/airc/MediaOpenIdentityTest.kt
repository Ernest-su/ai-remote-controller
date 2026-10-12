package ernest.airc

import kotlinx.coroutines.*
import kotlin.test.*

class MediaOpenIdentityTest {
    @Test fun rejectsDifferentPlayer() = verify("other.viewer","image/png","http")
    @Test fun rejectsDifferentMime() = verify("fixture.viewer","video/mp4","http")
    @Test fun rejectsDifferentTransport() = verify("fixture.viewer","image/png","file")
    @Test fun acceptsMatchingPlayerMimeAndTransport() = verify("fixture.viewer","image/png","http",true)

    private fun verify(player:String,mime:String,transport:String,valid:Boolean=false):Unit=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val media=FileMediaController(scope){tool,_->when(tool){
            "media.players"->"""{"root":"inbox","path":"picture.png","mimeType":"image/png","transport":"http","packages":["fixture.viewer"]}"""
            "media.open"->"""{"root":"inbox","path":"picture.png","mimeType":"$mime","transport":"$transport","package":"$player","launched":true,"playbackVerified":false,"resourceId":"owned"}"""
            else->error("Unexpected request")
        }}
        try {
            media.inspect("inbox","picture.png",true);media.open(true)
            if(valid) {
                assertNull(media.state.value.error);assertEquals("owned",media.state.value.resourceId)
                assertTrue(checkNotNull(media.state.value.message).contains("播放器已启动"))
            } else {
                assertEquals("播放器启动响应对象不匹配",media.state.value.error)
                assertNull(media.state.value.resourceId);assertNull(media.state.value.message)
            }
            assertFalse(media.state.value.busy)
        }finally{media.clear();scope.cancel()}
    }
}
