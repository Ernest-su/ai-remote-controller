package ernest.airc

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream

/** Emulator UI fixtures; actual PackageManager installation is covered separately. */
class AppPackagesUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun applicationIconRendersDecodedPng() {
        val bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        val png=ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        bitmap.recycle()
        var loaded=false
        compose.setContent { RemoteTheme { ApplicationIcon("test.app",true) { loaded=true;png } } }
        compose.waitUntil { loaded }
        compose.waitForIdle()
        val image=compose.onNodeWithTag("application-icon-test.app").captureToImage().asAndroidBitmap()
        assertEquals(android.graphics.Color.GREEN,image.getPixel(image.width/2,image.height/2))
    }
    @Test fun splitSelectionRequiresBaseAndExplicitSubmission() {
        val base=TargetApkInfo("inbox","base.apk","test.app",1,sizeBytes=10,sha256="a".repeat(64),signatures=emptyList(),signatureVerified=false)
        val split=base.copy(path="split.apk",split="config.en",sha256="b".repeat(64))
        val selected=mutableStateOf(listOf(split))
        var prepared=false
        compose.setContent { RemoteTheme { PageContent {
            AppInstallationPanel(AppInstallationState(selectedApks=selected.value),null,true,{}, {}, {},
                onRemove={selected.value=selected.value-it},onPrepareSet={prepared=true})
        } } }
        compose.onNodeWithText("准备安装整组 APK").performScrollTo().assertIsNotEnabled()
        assertFalse(prepared)
        compose.runOnIdle { selected.value=listOf(base,split) }
        compose.onNodeWithText("准备安装整组 APK").performScrollTo().assertIsEnabled().performClick()
        assertTrue(prepared)
        compose.onAllNodesWithText("移除此 APK")[0].performScrollTo().performClick()
        compose.onNodeWithText("准备安装整组 APK").performScrollTo().assertIsNotEnabled()
    }
}
