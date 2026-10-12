package ernest.airc

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Real system picker cancellation; only self-created target data is involved. */
class FilePickerTargetTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun pickerCancellationPreservesSourceAndVerifiedDownload()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments()
        val host=args.getString("fileTargetHost");val port=args.getString("fileTargetPort")?.toIntOrNull()
        assumeTrue("Explicit independent file target required",host!=null&&port!=null)
        lateinit var model:RemoteViewModel;var initialized=false;var deviceId:String?=null
        val runtime=RuntimeConnection();val transfer=RuntimeTransferClient(runtime)
        val name="picker-${java.util.UUID.randomUUID()}.bin";val bytes=ByteArray(64*1024){it.toByte()}
        suspend fun await(condition:()->Boolean){withTimeout(15000){while(!condition())delay(50)}}
        suspend fun cancelPicker(){
            await{instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let{it!=context.packageName}==true}
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await{instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName}
            compose.waitForIdle()
        }
        try {
            deviceId=runtime.connect(host!!,port!!).deviceId
            val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){(it.toInt()and 255).toString(16).padStart(2,'0')}
            assertEquals("completed",transfer.upload(name,bytes.size.toLong(),sha,{bytes.inputStream()}).phase)
            compose.runOnUiThread{
                model=ViewModelProvider(compose.activity)[RemoteViewModel::class.java];initialized=true
                model.discovered(DiscoveredService("Owned picker target",host,port,emptyMap()))
            }
            await{model.state.value.connected&&!model.state.value.busy}
            compose.onNodeWithTag("open-menu").performClick()
            compose.onNodeWithTag("feature-files").performScrollTo().performClick()
            compose.runOnUiThread{model.transfers.select(UploadSource(name,bytes.size.toLong()){bytes.inputStream()})}
            compose.onNodeWithTag("files-tools").performClick()
            compose.onNodeWithText("选择本地文件").performScrollTo().performClick()
            cancelPicker()
            assertEquals(name,model.transfers.state.value.sourceName)
            compose.runOnUiThread{model.transfers.download("inbox",name,context.cacheDir,true)}
            await{!model.transfers.state.value.running&&(model.transfers.state.value.readyToSave||model.transfers.state.value.error!=null)}
            assertNull(model.transfers.state.value.error);assertTrue(model.transfers.state.value.readyToSave)
            compose.onNodeWithText("保存已下载文件").performScrollTo().performClick()
            cancelPicker()
            assertTrue(model.transfers.state.value.readyToSave)
            compose.onNodeWithText("保存已下载文件").performScrollTo().assertIsEnabled()
            val export=model.transfers.prepareExport();assertEquals(name,export)
            val verified=checkNotNull(model.transfers.takeExport())
            assertTrue(verified.file.exists());assertArrayEquals(bytes,verified.file.readBytes())
            model.transfers.restoreExport(verified)
        }finally{
            transfer.close();runtime.close()
            if(initialized)compose.runOnUiThread{model.disconnect()}
            deviceId?.let{DeviceStore(context).remove(it)}
        }
    }
}
