package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*
import java.io.File

class RemoteTextTargetTest {
    @Test fun typedTextPreservesLiteralPercentAndShellSyntax():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Explicit target required",!serial.isNullOrBlank())
        val adb=HostAdb();val pkg="ernest.airc.mediafixture"
        check(adb.command("-s",serial!!,"shell","pm list packages --user all $pkg").lines().none{it=="package:$pkg"})
        val target=ShellRuntimeTarget(serial);val runtime=RuntimeConnection();var installed=false
        try {
            target.prepare()
            val apk=File(checkNotNull(System.getProperty("airc.mediaFixtureApk")))
            check(adb.command("-s",serial,"install",apk.absolutePath).contains("Success"));installed=true
            adb.command("-s",serial,"shell","am start -W -n $pkg/.TextActivity")
            delay(500)
            runtime.connect("127.0.0.1",checkNotNull(target.port))
            val text="hello %s; \$(reboot)"
            val result=Json.parseToJsonElement(runtime.request("tool","remote.text",buildJsonObject{put("text",text)})).jsonObject
            assertTrue(result["submitted"]!!.jsonPrimitive.boolean)
            assertFalse(result["contentVerified"]!!.jsonPrimitive.boolean)
            var actual=""
            withTimeoutOrNull(10000) {
                do {actual=adb.command("-s",serial,"shell","content read --uri content://ernest.airc.mediafixture.text/text");if(actual!=text)delay(100)}while(actual!=text)
            }
            assertEquals(text,actual)
            val hierarchy=Json.parseToJsonElement(runtime.request("tool","device.ui_hierarchy")).jsonObject
            assertTrue(hierarchy["readOnly"]!!.jsonPrimitive.boolean)
            assertFalse(hierarchy["atomicSnapshot"]!!.jsonPrimitive.boolean)
            val xml=hierarchy["xml"]!!.jsonPrimitive.content
            assertTrue(xml.contains(text));assertTrue(xml.contains("android.widget.EditText"))
            assertFails { runtime.request("tool","device.ui_hierarchy",buildJsonObject{put("path","/sdcard/arbitrary.xml")}) }
            assertFails { runtime.request("tool","remote.text",buildJsonObject{put("text","中文")}) }
            assertEquals(text,adb.command("-s",serial,"shell","content read --uri content://ernest.airc.mediafixture.text/text"))
        }finally {
            runtime.close()
            try {if(installed)check(adb.command("-s",serial,"uninstall",pkg).contains("Success"))}
            finally {target.close()}
        }
    }
}
