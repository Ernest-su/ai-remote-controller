package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** File/installation callbacks are local fixtures; never installs an application on a real target. */
class ApkFileSelectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val entries = listOf(TargetFileEntry("packages", "directory"), TargetFileEntry("TV.APK", "file", 10),
        TargetFileEntry("movie.mp4", "file", 10), TargetFileEntry("linked.apk", "file", 10, symlink = true))
    private val info = TargetApkInfo("downloads", "packages/TV.APK", "test.apk.selection", 1, "fixture", sizeBytes = 10, sha256 = "a".repeat(64), signatures = emptyList(), signatureVerified = false)

    @Test fun selectionNavigatesRestoresAndRequiresSeparateInstallationConfirmation() {
        var files by mutableStateOf(FileBrowserState(loaded = true, entries = entries))
        var selected by mutableStateOf<TargetApkInfo?>(null)
        var installation by mutableStateOf(AppInstallationState())
        var inspected = 0; var prepared = 0; var installed = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RemoteTheme(darkTheme = true) { Surface(Modifier.width(320.dp).height(640.dp)) {
                    FileBrowserPanel(files, true, {}, { files = files.copy(path = "", entries = entries) }, {},
                        { files = files.copy(path = "packages", entries = entries.filter { it.kind == "file" }) }, {},
                        apkInstallationAvailable = true, onApk = { inspected++; selected = info },
                        selectionBackHandler = { active, cancel -> androidx.activity.compose.BackHandler(active, onBack = cancel) },
                        transfer = { section -> if (section == "apk") {
                            ApkInspectionPanel(ApkInspectionState(info = selected))
                            AppInstallationPanel(installation, selected, true,
                                { update -> prepared++; installation = AppInstallationState(proposal = AppInstallProposal("fixture", "apps.install", info, userId = 0, update = update, approvalRequired = true, codeMayAffectAllUsers = false)) },
                                { accept -> if (accept) installed++; installation = AppInstallationState() }, {}, installLabel = "在被控设备安装此 APK")
                        } })
                } }
            }
        }
        openSelector()
        compose.onNodeWithText("movie.mp4").assertDoesNotExist()
        compose.onNodeWithText("linked.apk").assertDoesNotExist()
        compose.onNodeWithText("packages").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("files-apk-selection").performScrollTo().assertIsDisplayed()
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        File(directory, "apk-file-selection-dark.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("TV.APK").performScrollTo().performClick()
        assertEquals(1, inspected); assertEquals(0, prepared); assertEquals(0, installed)
        compose.onNodeWithText("在被控设备安装此 APK").performScrollTo().performClick()
        assertEquals(1, prepared); assertEquals(0, installed)
        compose.onNodeWithText("确认安装").performClick()
        assertEquals(1, installed)
        compose.onNodeWithTag("files-tools-dialog-close").performClick()
        compose.onNodeWithTag("files-apk-selection").assertDoesNotExist()
    }

    @Test fun cancelBackDeviceChangeAndOfflineNeverInspectOrInstall() {
        var available by mutableStateOf(true)
        var capable by mutableStateOf(true)
        var device by mutableStateOf("first")
        var readOnly by mutableStateOf(false)
        compose.setContent { RemoteTheme { Surface(Modifier.width(320.dp).height(640.dp)) {
            FileBrowserPanel(FileBrowserState(loaded = true, entries = entries, readOnly = readOnly), available, {}, {}, {}, {}, {},
                apkInstallationAvailable = capable, selectionDeviceKey = device, onApk = { error("Must not inspect") },
                selectionBackHandler = { active, cancel -> androidx.activity.compose.BackHandler(active, onBack = cancel) })
        } } }
        openSelector()
        compose.onNodeWithTag("files-apk-cancel").performClick()
        openSelector()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("files-apk-selection").assertDoesNotExist()
        openSelector()
        compose.runOnIdle { available = false }
        compose.onNodeWithText("TV.APK").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("files-apk-cancel").performClick()
        compose.onNodeWithTag("files-overflow").performClick()
        compose.onNodeWithTag("files-install-apk").assertIsNotEnabled()
        compose.runOnIdle { available = true; capable = false }
        compose.onNodeWithTag("files-install-apk").assertIsNotEnabled()
        compose.runOnIdle { capable = true; readOnly = true }
        compose.onNodeWithTag("files-install-apk").assertIsNotEnabled()
        compose.runOnIdle { readOnly = false }
        compose.onNodeWithTag("files-install-apk").performClick()
        compose.runOnIdle { device = "second" }
        compose.onNodeWithTag("files-apk-selection").assertDoesNotExist()
    }

    private fun openSelector() {
        compose.onNodeWithTag("files-overflow").performClick()
        compose.onNodeWithTag("files-install-apk").performClick()
        compose.onNodeWithTag("files-apk-selection").assertExists()
        compose.waitForIdle()
    }
}
