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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class FilePageLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun bottomOverflowAndDetectedDownloadInfoWorkInNarrowLargeTextBothThemes() {
        var dark by mutableStateOf(false)
        var page by mutableStateOf("files")
        var download by mutableStateOf(UrlDownloadState(url = "https://example.test/download", name = "电视应用.apk", infoReady = true, fileSize = 123456))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RemoteTheme(darkTheme = dark) {
                    Surface(Modifier.width(320.dp).height(640.dp).testTag("file-layout"), color = RemoteColors.Background) {
                        if (page == "files") FileBrowserPanel(FileBrowserState(entries = listOf(TargetFileEntry("电视应用.apk", "file", 123456)), loaded = true), true,
                            {}, {}, {}, {}, {}, onCreateDirectory = {}, transfer = { section -> Text("当前操作:$section") })
                        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            UrlDownloadPanel(download, true, true, { download = download.copy(url = it) }, { download = download.copy(name = it) }, {}, {}, {}, {})
                        }
                    }
                }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode; page = "files" }
            compose.onNodeWithText("文件管理").assertDoesNotExist()
            compose.onNodeWithTag("files-refresh").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
            capture("files", mode)
            compose.onNodeWithTag("files-overflow").performClick()
            compose.onNodeWithTag("files-overflow-menu").assertIsDisplayed()
            capture("menu", mode, "files-overflow-menu")
            compose.onNodeWithTag("files-storage").performClick()
            compose.onNodeWithText("当前操作:storage").assertIsDisplayed()
            compose.onNodeWithTag("files-tool-url").assertDoesNotExist()
            compose.onNodeWithTag("files-tools-dialog-close").performClick()
            compose.runOnIdle { page = "url" }
            compose.onNodeWithTag("url-download-name").performScrollTo().assertTextContains("电视应用.apk")
            compose.onNodeWithText("120.5 KiB").performScrollTo().assertIsDisplayed()
            capture("url", mode)
            compose.onNodeWithTag("url-download-name").performScrollTo().performTextReplacement("我的电视.apk")
            compose.onNodeWithTag("url-download-name").assertTextContains("我的电视.apk")
            compose.runOnIdle { download = download.copy(name = "电视应用.apk") }
        }
    }
    private fun capture(name: String, dark: Boolean, tag: String = "file-layout") {
        val output = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "file-page-$name-${if (dark) "dark" else "light"}.png")
        output.outputStream().use { compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
