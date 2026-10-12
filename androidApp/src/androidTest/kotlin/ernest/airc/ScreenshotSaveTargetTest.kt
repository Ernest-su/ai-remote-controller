package ernest.airc

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.Assert.*

@RunWith(Parameterized::class)
class ScreenshotSaveTargetTest(private val disconnectBeforeSave:Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="disconnectBeforeSave={0}")
        fun cases()=listOf(arrayOf<Any>(false),arrayOf<Any>(true))
    }
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun targetScreenshotSavesThroughSystemPickerWithIdenticalPng()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments();val host=args.getString("fileTargetHost");val port=args.getString("fileTargetPort")?.toIntOrNull()
        assumeTrue("Explicit independent screenshot target required",host!=null&&port!=null)
        val name="airc-shot-${java.util.UUID.randomUUID()}.png";val path="/sdcard/Download/$name"
        fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use{it.readText()}
        assertEquals("",shell("ls $path").trim())
        val picker=SystemFilePicker(instrumentation);val observer=RuntimeConnection()
        lateinit var model:RemoteViewModel;var initialized=false;var id:String?=null
        suspend fun await(condition:()->Boolean){withTimeout(15000){while(!condition())delay(50)}}
        try {
            id=observer.connect(host!!,port!!).deviceId
            compose.runOnUiThread{model=ViewModelProvider(compose.activity)[RemoteViewModel::class.java];initialized=true;model.discovered(DiscoveredService("Owned screenshot target",host,port,emptyMap()))}
            await{model.state.value.connected&&!model.state.value.busy}
            compose.onNodeWithTag("open-menu").performClick();compose.onNodeWithTag("feature-screenshot").performScrollTo().performClick()
            compose.onNodeWithText("获取 / 刷新截图").performScrollTo().performClick()
            await{!model.screenshots.state.value.loading&&(model.screenshots.state.value.png!=null||model.screenshots.state.value.error!=null)}
            assertNull(model.screenshots.state.value.error);val png=checkNotNull(model.screenshots.state.value.png)
            val bitmap=checkNotNull(BitmapFactory.decodeByteArray(png,0,png.size));assertTrue(bitmap.width>0&&bitmap.height>0);bitmap.recycle()
            compose.waitUntil(15000){compose.onAllNodesWithTag("screenshot-preview",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("screenshot-preview",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("保存 PNG").performScrollTo().performClick()
            if(disconnectBeforeSave){
                instrumentation.runOnMainSync{model.disconnect()}
                await{!model.state.value.connected&&model.screenshots.state.value.png==null}
            }
            picker.downloads()
            val field=picker.filenameField()
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name.removeSuffix(".png"))}))
            delay(300);picker.click(picker.saveButton())
            await{instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName}
            compose.waitUntil(15000){compose.onAllNodesWithText("截图已保存").fetchSemanticsNodes().isNotEmpty()}
            compose.waitForIdle();compose.onNodeWithText("截图已保存").performScrollTo().assertIsDisplayed()
            assertEquals(png.size.toString(),shell("stat -c %s $path").trim())
            val sha=MessageDigest.getInstance("SHA-256").digest(png).joinToString(""){(it.toInt()and 255).toString(16).padStart(2,'0')}
            assertEquals(sha,shell("sha256sum $path").trim().substringBefore(' '))
        }finally{observer.close();if(initialized)compose.runOnUiThread{model.disconnect()};shell("rm -f -- $path $path.png");id?.let{DeviceStore(context).remove(it)}}
    }
}
