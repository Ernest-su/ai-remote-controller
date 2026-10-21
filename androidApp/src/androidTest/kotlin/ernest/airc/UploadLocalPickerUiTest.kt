package ernest.airc

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UploadLocalPickerUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun uploadOpensSystemPickerAndCancellationPreservesDraft() {
        lateinit var model: RemoteViewModel
        compose.runOnUiThread {
            model=ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
            model.uploadDraft.select(listOf(UploadSource("one.txt",1){byteArrayOf(1).inputStream()},UploadSource("two.txt",1){byteArrayOf(2).inputStream()}))
        }
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-files").performScrollTo().performClick()
        compose.onNodeWithTag("files-upload").performClick()
        compose.onNodeWithText("选择本地文件").assertIsEnabled().performClick()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val targetPackage=instrumentation.targetContext.packageName
        fun awaitPicker(expected: Boolean) {
            val deadline=SystemClock.uptimeMillis()+10000
            while(SystemClock.uptimeMillis()<deadline) {
                val active=instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
                if(active!=null && (active!=targetPackage)==expected) return
                SystemClock.sleep(100)
            }
            error("System picker state did not become $expected")
        }
        try { awaitPicker(true) } finally { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }
        awaitPicker(false)
        compose.onNodeWithText("选择本地文件").assertIsEnabled()
        compose.runOnIdle { assertEquals(listOf("one.txt","two.txt"),model.uploadDraft.state.value.map { it.name }) }
        compose.onNodeWithTag("files-tools-dialog-close").performClick()
        compose.onNodeWithTag("files-upload").performClick()
        compose.onNodeWithText("已选择：one.txt、two.txt").assertExists()
    }
}
