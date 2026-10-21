package ernest.airc

import android.content.ContentValues
import android.provider.MediaStore
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
import org.junit.Assert.*

/** Real platform picker selection/save, using only UUID files in Downloads. */
class FilePickerSuccessTargetTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun selectedDocumentUploadsAndDownloadedDocumentSaves()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments();val host=args.getString("fileTargetHost");val port=args.getString("fileTargetPort")?.toIntOrNull()
        assumeTrue("Explicit independent file target required",host!=null&&port!=null)
        val suffix=java.util.UUID.randomUUID().toString();val sourceName="airc-source-$suffix.bin";val savedName="airc-saved-$suffix.bin"
        val savedPath="/sdcard/Download/$savedName";val resolver=context.contentResolver
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use{it.readText()}
        assertEquals("",shell("ls $savedPath").trim())
        val source=checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{
            put(MediaStore.MediaColumns.DISPLAY_NAME,sourceName);put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");put(MediaStore.MediaColumns.IS_PENDING,1)
        }))
        var initialized=false;lateinit var model:RemoteViewModel;var deviceId:String?=null;val observer=RuntimeConnection()
        val bytes=ByteArray(64*1024){it.toByte()};val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){(it.toInt()and 255).toString(16).padStart(2,'0')}
        var stage="connect"
        suspend fun await(condition:()->Boolean){try{withTimeout(15000){while(!condition())delay(50)}}catch(error:TimeoutCancellationException){throw AssertionError("Timed out at $stage",error)}}
        val picker=SystemFilePicker(instrumentation)
        try {
            checkNotNull(resolver.openOutputStream(source)).use{it.write(bytes)}
            resolver.update(source,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            deviceId=observer.connect(host!!,port!!).deviceId
            compose.runOnUiThread{model=ViewModelProvider(compose.activity)[RemoteViewModel::class.java];initialized=true;model.discovered(DiscoveredService("Owned successful picker target",host,port,emptyMap()))}
            await{model.state.value.connected&&!model.state.value.busy}
            compose.onNodeWithTag("open-menu").performClick();compose.onNodeWithTag("feature-files").performScrollTo().performClick()
            compose.onNodeWithTag("files-upload").performClick()
            compose.onNodeWithText("选择本地文件").performScrollTo().performClick()
            picker.downloads();stage="source selection";println("picker-stage: select owned source");delay(300);picker.click(picker.locate{it.text?.toString()?.replace("\n","")?.startsWith("airc-source-${suffix.take(8)}")==true})
            await{model.uploadDraft.state.value.singleOrNull()?.name==sourceName};compose.waitForIdle()
            compose.runOnUiThread{model.transfers.select(model.uploadDraft.state.value.single());model.transfers.upload("inbox",savedName,true)}
            await{!model.transfers.state.value.running&&model.transfers.state.value.phase in listOf("completed","failed")}
            assertNull(model.transfers.state.value.error);assertEquals("completed",model.transfers.state.value.phase)
            compose.runOnUiThread{model.transfers.download("inbox",savedName,context.cacheDir,true)}
            await{!model.transfers.state.value.running&&(model.transfers.state.value.readyToSave||model.transfers.state.value.error!=null)}
            assertNull(model.transfers.state.value.error)
            compose.onNodeWithText("保存已下载文件").performScrollTo().performClick()
            picker.downloads();stage="save button";println("picker-stage: save owned destination");picker.saveButton()
            val filename=picker.filenameField()
            assertTrue(filename.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,savedName)}))
            delay(300)
            picker.click(picker.saveButton())
            stage="save completion";println("picker-stage: Save clicked")
            stage="return to app"
            println("picker-stage: active package=${instrumentation.uiAutomation.rootInActiveWindow?.packageName}")
            await{instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName}
            compose.waitForIdle()
            stage="destination file"
            await{shell("stat -c %s $savedPath").trim()==bytes.size.toString()}
            println("picker-stage: destination exists")
            compose.waitUntil(15000){compose.onAllNodesWithText("文件已保存").fetchSemanticsNodes().isNotEmpty()}
            compose.waitForIdle();compose.onNodeWithText("文件已保存").performScrollTo().assertIsDisplayed()
            assertEquals(sha,shell("sha256sum $savedPath").trim().substringBefore(' '))
        }finally{
            observer.close();if(initialized)compose.runOnUiThread{model.disconnect()}
            try{resolver.delete(source,null,null)}finally{shell("rm -f -- $savedPath")}
            deviceId?.let{DeviceStore(context).remove(it)}
        }
    }
}
