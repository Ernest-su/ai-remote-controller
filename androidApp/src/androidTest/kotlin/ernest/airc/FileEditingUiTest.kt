package ernest.airc

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

class FileEditingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun renameValidatesRestoresAndKeepsErrorsUntilSuccessfulRefresh() {
        var files by mutableStateOf(FileBrowserState(loaded = true, entries = listOf(TargetFileEntry("old.txt", "file"), TargetFileEntry("taken.txt", "file"))))
        var calls = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RemoteTheme { Surface(Modifier.width(320.dp).height(640.dp)) {
            FileBrowserPanel(files, true, {}, {}, {}, {}, {}, onRename = { entry, name ->
                calls++
                files = if (calls == 1) files.copy(error = "fixture rename failure") else files.copy(error = null, entries = files.entries.map { if(it.name == entry.name) it.copy(name = name) else it })
            })
        } } }
        compose.onNodeWithTag("file-more-old.txt").performScrollTo().performClick()
        compose.onNodeWithText("重命名").performClick()
        compose.onNodeWithTag("files-rename-name").assertTextContains("old.txt").performTextReplacement("../escape")
        compose.onAllNodesWithText("重命名").filter(hasClickAction()).onFirst().assertIsNotEnabled()
        assertEquals(0, calls)
        compose.onNodeWithTag("files-rename-name").performTextReplacement("taken.txt")
        compose.onNodeWithText("同名文件或目录已存在，请更换名称。").assertExists()
        compose.onNodeWithTag("files-rename-name").performTextReplacement("new.txt")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("files-rename-name").assertTextContains("new.txt")
        compose.onAllNodesWithText("重命名").filter(hasClickAction()).onFirst().performClick()
        compose.onNode(hasText("fixture rename failure") and hasAnyAncestor(hasTestTag("files-rename-dialog"))).assertExists()
        compose.onAllNodesWithText("重命名").filter(hasClickAction()).onFirst().performClick()
        compose.onNodeWithTag("files-rename-dialog").assertDoesNotExist()
        compose.onNodeWithText("new.txt").assertExists()
    }

    @Test fun unknownFileTextEditingRestoresAndRequiresExplicitSaveOrDiscard() {
        var editor by mutableStateOf(FileTextState())
        var saves = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RemoteTheme(darkTheme = true) { Surface(Modifier.width(320.dp).height(640.dp)) {
                    FileBrowserPanel(FileBrowserState(loaded = true, entries = listOf(TargetFileEntry("source.custom", "file"))), true, {}, {}, {}, {}, {},
                        onText = { editor = FileTextState(open = true, path = it.name, sizeBytes = 11, text = "const x = 1", draft = "const x = 1", writable = true) })
                    FileTextEditor(editor, FileTransferState(), true, { editor = editor.copy(draft = it) }, { saves++ }, {}, {}, { editor = FileTextState() }, {}, {})
                } }
            }
        }
        compose.onNodeWithTag("file-more-source.custom").performScrollTo().performClick()
        compose.onNodeWithText("以文本方式打开").performClick()
        compose.onNodeWithTag("file-text-content").assertTextContains("const x = 1")
        captureText("view")
        compose.onNodeWithTag("file-text-edit").performScrollTo().performClick()
        compose.onNodeWithTag("file-text-input").performScrollTo().performClick().performTextReplacement("const x = 2")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("file-text-input").assertTextContains("const x = 2")
        assertEquals(0, saves)
        captureText("edit")
        compose.onNodeWithText("保存回被控设备").performScrollTo().performClick()
        assertEquals(1, saves)
        compose.onNodeWithTag("file-text-editor-close").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("file-text-editor").assertExists()
        compose.onNodeWithTag("file-text-editor-close").performClick()
        compose.onNodeWithText("放弃并关闭").performClick()
        compose.onNodeWithTag("file-text-editor").assertDoesNotExist()
    }

    @Test fun largeFileWarningRequiresConsentAndPartialPreviewCannotEdit() {
        var state by mutableStateOf(FileTextState(open = true, path = "large.log", sizeBytes = 100_000_000, warning = true))
        var loads = 0
        compose.setContent { RemoteTheme { FileTextEditor(state, FileTransferState(), true, {}, { error("Must not save") },
            { loads++; state = state.copy(warning = false, partial = true, text = "prefix", draft = "prefix", writable = false) }, {}, {}, {}, {}) } }
        assertEquals(0, loads)
        compose.onNodeWithText("继续加载").performScrollTo().performClick()
        assertEquals(1, loads)
        compose.onNodeWithTag("file-text-edit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("保存回被控设备").assertDoesNotExist()
    }
    private fun captureText(name: String) {
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        File(directory, "file-text-$name-dark.png").outputStream().use {
            compose.onNodeWithTag("file-text-editor").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

}
