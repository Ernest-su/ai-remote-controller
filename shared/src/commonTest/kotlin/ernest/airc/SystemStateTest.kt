package ernest.airc
import kotlin.test.*
class SystemStateTest {
    @Test fun observationValidityAndUnknownLabels(){
        val unknown=TargetSystemState(2000,true,false,"android_framework")
        assertTrue(validSystemObservation(unknown));assertEquals("未知",systemBooleanLabel(null));assertEquals("否",systemBooleanLabel(false))
        assertFalse(validSystemObservation(unknown.copy(interactive=false)))
        assertFalse(validSystemObservation(unknown.copy(readOnly=false)))
        assertTrue(validSystemObservation(unknown.copy(available=true,userId=0,elapsedRealtimeMs=12,interactive=false)))
        assertFalse(validSystemObservation(unknown.copy(available=true,userId=1,elapsedRealtimeMs=12,interactive=true)))
    }
}
