package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class VirtualGamepadComposeTargetTest {
    @get:Rule val compose=createComposeRule()
    @Test fun touchEdgesBothSticksAndPageDisposalReachAndroid():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned Compose-to-Android input",!serial.isNullOrBlank())
        val target=VirtualGamepadTarget(serial!!)
        val stateDirectory=Files.createTempDirectory("airc-compose-gamepad-")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val controller=DesktopController(store=DesktopProfileStore(stateDirectory),deviceNames=DesktopDeviceNames(stateDirectory),scope=scope)
        var visible by mutableStateOf(true)
        var focused by mutableStateOf(true)
        val windowInfo=object:WindowInfo { override val isWindowFocused get()=focused }
        fun JsonObject.int(key:String)=getValue(key).jsonPrimitive.int
        suspend fun key(code:Int,action:Int)=target.awaitEvent{it["kind"]!!.jsonPrimitive.content=="key"&&it.int("code")==code&&it.int("action")==action}
        try {
            target.prepare()
            compose.setContent { RemoteTheme { Box(Modifier.width(390.dp)) {
                val state by controller.state.collectAsState()
                CompositionLocalProvider(LocalWindowInfo provides windowInfo) {
                    if(visible) Column(Modifier.fillMaxSize()) {
                        val gamepad by controller.virtualGamepad.state.collectAsState()
                        val available = gamepadAvailable(state.connected, state.tools)
                        GamepadSessionButton(gamepad, available,
                            { controller.virtualGamepad.start(available, allowSynthetic = true) }, { controller.virtualGamepad.stop() })
                        Box(Modifier.weight(1f)) { DesktopGamepad(state,controller) }
                    }
                }
            } } }
            controller.connectLan("127.0.0.1",checkNotNull(target.port))
            assertEquals(0,withTimeout(7000){controller.state.first{it.connected}}.uid)
            compose.onNodeWithContentDescription("开始手柄").performClick()
            val active=withTimeout(7000){controller.virtualGamepad.state.first{it.running&&!it.starting}}
            var name=checkNotNull(active.deviceName);delay(700)
            val a=compose.onNodeWithTag("virtual-button-a")
            a.performTouchInput{down(center)}
            val down=key(96,0);assertEquals(name,down["deviceName"]!!.jsonPrimitive.content);assertTrue(down.int("deviceId")>0)
            delay(400)
            a.performTouchInput{up()}
            val up=key(96,1)
            assertTrue(up["eventTime"]!!.jsonPrimitive.long-down["eventTime"]!!.jsonPrimitive.long>=400)
            for((tag,axis) in listOf("left" to "x","right" to "z")) {
                val stick=compose.onNodeWithTag("virtual-stick-$tag")
                stick.performTouchInput{down(centerRight)}
                val moved=target.awaitEvent{it["kind"]!!.jsonPrimitive.content=="motion"&&it["deviceName"]!!.jsonPrimitive.content==name&&it[axis]!!.jsonPrimitive.double>.8}
                stick.performTouchInput{up()}
                target.awaitEvent{it["kind"]!!.jsonPrimitive.content=="motion"&&it["eventTime"]!!.jsonPrimitive.long>moved["eventTime"]!!.jsonPrimitive.long&&kotlin.math.abs(it[axis]!!.jsonPrimitive.double)<.02}
            }
            val x=compose.onNodeWithTag("virtual-button-x")
            x.performTouchInput{down(center)}
            key(99,0)
            compose.runOnIdle{focused=false}
            withTimeout(7000){controller.virtualGamepad.state.first{!it.running&&!it.stopping}}
            key(99,1);target.awaitRemoved(name)
            x.performTouchInput{up()}
            compose.runOnIdle{focused=true}
            delay(400)
            assertFalse(controller.virtualGamepad.state.value.running,"Focus restoration restarted input")
            target.awaitRemoved(name)
            val previous=name
            compose.onNodeWithContentDescription("开始手柄").performClick()
            name=checkNotNull(withTimeout(7000){controller.virtualGamepad.state.first{it.running&&!it.starting&&it.deviceName!=previous}}.deviceName)
            delay(700)
            val b=compose.onNodeWithTag("virtual-button-b")
            b.performTouchInput{down(center)}
            key(97,0)
            compose.runOnIdle{visible=false}
            withTimeout(7000){controller.virtualGamepad.state.first{!it.running&&!it.stopping}}
            key(97,1);target.awaitRemoved(name)
            assertEquals(VirtualGamepadInput(),controller.virtualGamepad.state.value.input)
        } finally {
            try {controller.close()}
            finally {try {target.close()} finally {stateDirectory.toFile().deleteRecursively()}}
        }
    }
}
