package ernest.airc.desktop

import ernest.airc.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Historical opt-in read-only probe of the existing service at 9876; never deploys or reads keys. */
class LiveModelTargetTest {
    @Test fun configuredTargetProviderCallsReadonlyInfoAndReturnsMatchingFields():Unit=runBlocking {
        assumeTrue("Set AIRC_LIVE_MODEL_TEST=1 for real provider requests",System.getenv("AIRC_LIVE_MODEL_TEST")=="1")
        val serial=System.getProperty("airc.targetSerial");assumeTrue("Set -PaircTargetSerial",!serial.isNullOrBlank())
        val host=DesktopDiscovery.tcpAdbHost(serial!!);assumeTrue("TCP target address required",host!=null)
        val runtime=RuntimeConnection();val phases=CopyOnWriteArrayList<String>()
        try {
            val status=runtime.connect(host!!,9876)
            assertTrue(status.textToolScope,"Target must enforce request tool scope")
            assertTrue(status.modelConfigured,"Target model must already be configured")
            val baseline=Json.decodeFromString<DeviceInfo>(runtime.request("tool","device.info"))
            val model=checkNotNull(baseline.model);val sdk=checkNotNull(baseline.sdk)
            val answer=withTimeout(180000){runtime.request("text",arguments=buildJsonObject{put("allowedTools",JsonArray(listOf(JsonPrimitive("device.info"))))},text="这是只读验收。请调用 device_info 工具读取当前设备信息，原样报告 model 与 sdk 两个字段。只允许读取，不要执行 shell，不要修改、启动、停止、锁屏、重启或关机。",onProgress={phases+=it.phase})}
            assertTrue(answer.isNotBlank());assertTrue("tool" in phases,"Real task must report a tool decision")
            assertTrue(answer.contains(model),"Provider answer must match independently read model")
            assertTrue(answer.contains(sdk.toString()),"Provider answer must match independently read SDK")
            assertTrue(runtime.connected.value)
        }finally{runtime.close()}
    }
}
