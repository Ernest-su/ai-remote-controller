package ernest.airc

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class StorageFriendlyUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val report=StorageReport("inbox","folder",StorageTotals(2,60L*1024*1024,61L*1024*1024),
        mapOf("video" to StorageTotals(1,50L*1024*1024,50L*1024*1024),"images" to StorageTotals(1,10L*1024*1024,11L*1024*1024)),
        listOf(StorageLargeFile("folder/movie.mp4","video",50L*1024*1024,50L*1024*1024)),3,1,0,0,0,0,0,0,emptyList(),0,false,true,false)

    @Test fun friendlySummaryAndFileRowsAdaptToLargeTextInBothThemes() {
        var dark by mutableStateOf(false)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.5f)) {
            RemoteTheme(dark) { Surface(Modifier.width(320.dp).height(640.dp)) { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                StorageAnalysisPanel(StorageAnalysisState(root="inbox",path="folder",report=report),true,{},{},onDelete={})
            } } }
        } }
        for(mode in listOf(false,true)) {
            compose.runOnIdle {dark=mode}
            compose.onNodeWithText("文件总大小").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("逻辑大小").assertDoesNotExist()
            compose.onNodeWithText("已分配文件块").assertDoesNotExist()
            compose.onNodeWithText("实际占用空间").assertDoesNotExist()
            compose.onNodeWithTag("storage-file-folder/movie.mp4").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("storage-delete-folder/movie.mp4").assertIsEnabled().assertIsDisplayed().assertWidthIsAtLeast(48.dp)
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"storage-friendly-${if(mode) "dark" else "light"}.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
            }
            compose.onNodeWithTag("storage-details").performScrollTo().performClick()
            compose.onNodeWithText("实际占用空间").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("storage-details").performScrollTo().performClick()
        }
    }

    @Test fun deletionRequiresConfirmationAndOfflineCannotSubmit() {
        var state by mutableStateOf(StorageAnalysisState(root="inbox",path="folder",report=report))
        var online by mutableStateOf(false)
        var proposal by mutableStateOf<FileActionProposal?>(null)
        var deleted=0
        compose.setContent { RemoteTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            StorageAnalysisPanel(state,online,{},{},onDelete={file ->
                proposal=FileActionProposal("fixture","delete",report.root,file.path,exists=true)
            })
            proposal?.let { action -> FileTransferPanel(FileTransferState(operation="delete",proposal=action),"downloads","unrelated",online,{},{},{},{},operation="delete",tasksOnly=true,onConfirm={accepted ->
                if(accepted) {deleted++;state=state.copy(outdated=true,report=report.copy(largestFiles=emptyList()))}
                proposal=null
            }) }
        } } }
        compose.onNodeWithTag("storage-delete-folder/movie.mp4").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { online=true }
        compose.onNodeWithTag("storage-delete-folder/movie.mp4").performClick()
        compose.onNodeWithText("确认删除").assertIsDisplayed()
        compose.runOnIdle { assertEquals("folder/movie.mp4",proposal!!.path);assertEquals("inbox",proposal!!.root);assertEquals(0,deleted) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("movie.mp4").assertExists()
        compose.onNodeWithTag("storage-delete-folder/movie.mp4").performScrollTo().performClick()
        compose.onNodeWithText("确认").performClick()
        compose.onNodeWithText("movie.mp4").assertDoesNotExist()
        compose.onNodeWithTag("storage-outdated").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {assertEquals(1,deleted);assertEquals(report.totals,state.report!!.totals)}
    }
}
