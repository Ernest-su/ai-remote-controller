package ernest.airc

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class ScreenshotShareTargetTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun systemChooserGrantsSeparateTestUidReadAccessToMatchingPng()=runBlocking{
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments();val host=args.getString("fileTargetHost");val port=args.getString("fileTargetPort")?.toIntOrNull()
        assumeTrue("Explicit independent screenshot target required",host!=null&&port!=null)
        fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use{it.readText()}
        val receiver=instrumentation.context.packageName
        shell("run-as $receiver rm -f files/share-receipt.json")
        val directory=java.io.File(context.cacheDir,"screenshots");val before=directory.listFiles()?.map{it.name}?.toSet().orEmpty()
        val picker=SystemFilePicker(instrumentation);val observer=RuntimeConnection()
        lateinit var model:RemoteViewModel;var initialized=false;var id:String?=null
        suspend fun await(condition:()->Boolean){withTimeout(15000){while(!condition())delay(50)}}
        try{
            id=observer.connect(host!!,port!!).deviceId
            compose.runOnUiThread{model=ViewModelProvider(compose.activity)[RemoteViewModel::class.java];initialized=true;model.discovered(DiscoveredService("Owned screenshot share target",host,port,emptyMap()))}
            await{model.state.value.connected&&!model.state.value.busy}
            compose.onNodeWithTag("open-menu").performClick();compose.onNodeWithTag("feature-screenshot").performScrollTo().performClick()
            compose.onNodeWithText("获取 / 刷新截图").performScrollTo().performClick()
            await{!model.screenshots.state.value.loading&&(model.screenshots.state.value.png!=null||model.screenshots.state.value.error!=null)}
            assertNull(model.screenshots.state.value.error);val png=checkNotNull(model.screenshots.state.value.png)
            compose.waitUntil(15000){compose.onAllNodesWithText("分享截图",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("分享截图",useUnmergedTree=true).performScrollTo().performClick()
            await{instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let{it!=context.packageName}==true}
            picker.click(picker.locate{it.text?.toString()?.replace(" ","")?.replace("\n","")=="AIRC截图验收"})
            var received=""
            await{received=shell("run-as $receiver cat files/share-receipt.json");received.isNotBlank()}
            val receipt=Json.parseToJsonElement(received).jsonObject
            assertFalse("Test receiver must open the granted URI",receipt.containsKey("error"))
            assertNotEquals(android.os.Process.myUid(),receipt["uid"]!!.jsonPrimitive.int)
            assertEquals(png.size.toLong(),receipt["bytes"]!!.jsonPrimitive.long)
            val sha=MessageDigest.getInstance("SHA-256").digest(png).joinToString(""){(it.toInt()and 255).toString(16).padStart(2,'0')}
            assertEquals(sha,receipt["sha256"]!!.jsonPrimitive.content)
        }finally{
            observer.close();if(initialized)compose.runOnUiThread{model.disconnect()}
            shell("run-as $receiver rm -f files/share-receipt.json")
            directory.listFiles()?.filter{it.name !in before}?.forEach{it.delete()}
            id?.let{DeviceStore(context).remove(it)}
        }
    }
}
