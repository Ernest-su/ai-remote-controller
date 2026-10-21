package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ScheduleProtocolTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun manualTasksPersistAfterDisconnectAndServiceRestart()=runBlocking {
        val binary=File(requireNotNull(System.getProperty("airc.hostRuntime")));val port=ServerSocket(0).use {it.localPort}
        val state=temporary.newFolder("schedule-state");val log=temporary.newFile("schedule-runtime.log")
        fun start()=ProcessBuilder(binary.path,"serve","--no-mdns","--state-dir",state.path,"--port",port.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
        fun stop(process:Process){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS)}}
        var process=start();val runtime=RuntimeConnection()
        suspend fun connect(){withTimeout(10000){while(runCatching {runtime.connect("127.0.0.1",port)}.isFailure){check(process.isAlive){log.readText()};delay(50)}}}
        try {
            connect();val identity=runtime.session.value!!.deviceId
            val saved=Json.parseToJsonElement(runtime.commandWriter()("schedule_save",buildJsonObject {put("kind","delay");put("delaySeconds",60);put("action","set_volume");put("volumePercent",37);put("confirmed",true)})).jsonObject
            runtime.disconnect();assertTrue(File(state,"schedules.json").readText().contains(saved["id"]!!.jsonPrimitive.content))
            stop(process);process=start();connect();assertEquals(identity,runtime.session.value!!.deviceId)
            val tasks=Json.parseToJsonElement(runtime.commandWriter()("schedule_list",buildJsonObject {})).jsonObject["tasks"]!!.jsonArray
            assertEquals(37,tasks[0].jsonObject["volumePercent"]!!.jsonPrimitive.int);assertEquals(1,tasks.size);assertEquals(saved["id"],tasks[0].jsonObject["id"]);assertEquals(true,tasks[0].jsonObject["enabled"]!!.jsonPrimitive.boolean)
            runtime.commandWriter()("schedule_delete",buildJsonObject {put("id",saved["id"]!!)})
            assertEquals("[]",File(state,"schedules.json").readText())
        }finally {runtime.close();stop(process)}
    }
}
