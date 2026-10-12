package ernest.airc
import kotlinx.coroutines.*
import kotlin.test.*
class SystemObservationControllerTest {
    @Test fun cancelledReadOnlyQueryCanBeRetried():Unit=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var calls=0
        val controller=SystemObservationController(scope){
            calls++
            if(calls==1)throw CancellationException("owned observation cancelled")
            """{"runtimeUid":2000,"readOnly":true,"available":true,"source":"android_framework","userId":0,"elapsedRealtimeMs":1000,"interactive":true,"deviceLocked":null}"""
        }
        try {
            controller.refresh(true)
            assertFalse(controller.state.value.loading)
            assertNull(controller.state.value.error)
            assertNull(controller.state.value.observation)
            controller.refresh(true)
            assertEquals(2,calls)
            assertFalse(controller.state.value.loading)
            assertEquals(true,controller.state.value.observation?.interactive)
            assertNotNull(controller.state.value.receivedAt)
        }finally{controller.clear();scope.cancel()}
    }

    @Test fun unknownValuesStayUnknownAndOldReplyCannotRestoreClearedState():Unit=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var response="""{"runtimeUid":2000,"readOnly":true,"available":true,"source":"android_framework","userId":0,"elapsedRealtimeMs":1000,"interactive":false,"deviceLocked":null}"""
        val controller=SystemObservationController(scope){response}
        try{
            controller.refresh(false);assertNull(controller.state.value.observation)
            controller.refresh(true);assertNotNull(controller.state.value.receivedAt);assertEquals(false,controller.state.value.observation!!.interactive);assertNull(controller.state.value.observation!!.deviceLocked)
            response="""{"runtimeUid":0,"readOnly":false,"available":false,"source":"android_framework"}"""
            controller.refresh(true);assertNull(controller.state.value.observation);assertNotNull(controller.state.value.error)
            val deferred=CompletableDeferred<String>()
            val late=SystemObservationController(scope){withContext(NonCancellable){deferred.await()}}
            late.refresh(true);late.clear();deferred.complete("""{"runtimeUid":0,"readOnly":true,"available":false,"source":"android_framework"}""");yield()
            assertNull(late.state.value.receivedAt);assertNull(late.state.value.observation);assertFalse(late.state.value.loading)
        }finally{controller.clear();scope.cancel()}
    }
}
