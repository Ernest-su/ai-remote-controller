package ernest.airc.desktop
import ernest.airc.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.*

class SystemObservationUiTargetTest {
    @get:Rule val compose=createComposeRule()
    @Test fun devicePageRefreshReadsTargetAndDisconnectRemovesObservations():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial");assumeTrue("Set -PaircTargetSerial",!serial.isNullOrBlank())
        val target=VirtualGamepadTarget(serial!!);val directory=Files.createTempDirectory("airc-system-observation-")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val controller=DesktopController(store=DesktopProfileStore(directory),deviceNames=DesktopDeviceNames(directory),scope=scope)
        try {
            target.prepare()
            compose.setContent{RemoteTheme{val state by controller.state.collectAsState();DesktopDeviceInfo(state,controller)}}
            controller.connectLan("127.0.0.1",checkNotNull(target.port))
            withTimeout(7000){controller.state.first{it.connected&&!it.busy}}
            compose.onNodeWithText("刷新电源与锁屏状态").performScrollTo().performClick()
            val loaded=withTimeout(7000){controller.systemObservation.state.first{it.observation!=null||it.error!=null}}
            assertNull(loaded.error);val observed=checkNotNull(loaded.observation)
            assertTrue(validSystemObservation(observed));assertEquals(0,observed.runtimeUid)
            assertTrue(observed.available)
            compose.onNodeWithText("可交互").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("设备已锁定").performScrollTo().assertIsDisplayed()
            controller.disconnect();withTimeout(7000){controller.state.first{!it.connected&&!it.busy}}
            withTimeout(3000){controller.systemObservation.state.first{it.observation==null}}
            compose.waitForIdle();compose.onNodeWithText("设备已锁定").assertDoesNotExist()
        }finally{controller.close();scope.cancel();target.close();directory.toFile().deleteRecursively()}
    }
}
