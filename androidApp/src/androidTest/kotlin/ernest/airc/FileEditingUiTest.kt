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
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("file-text-content").assertTextContains("const x = 1")
        captureText("view")
        compose.onNodeWithTag("file-text-edit").performClick()
        compose.onNodeWithTag("file-text-input").performClick().performTextReplacement("const x = 2")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("file-text-input").assertTextContains("const x = 2")
        assertEquals(0, saves)
        captureText("edit")
        compose.onNodeWithTag("file-text-save").performClick()
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
        compose.onNodeWithText("继续加载").performClick()
        assertEquals(1, loads)
        compose.onNodeWithTag("file-text-edit").assertIsNotEnabled()
        compose.onNodeWithTag("file-text-save").assertDoesNotExist()
    }
    @Test fun fiftySevenKbEditorScrollsWithoutReplacingBufferAndRestoresViewport() {
        val source = buildString { repeat(2500) { append("const line_$it = 1234567890; // editing fixture\n") } }.take(57 * 1024)
        var document by mutableStateOf(FileTextState(open = true, path = "57kb.txt", sizeBytes = source.length.toLong(),
            text = source, draft = source, writable = true))
        var dark by mutableStateOf(false)
        var backRequests by mutableIntStateOf(0)
        var saves = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RemoteTheme(darkTheme = dark) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().imePadding()) {
                FileTextEditor(document, FileTransferState(), true, { document = document.copy(draft = it) }, { saves++ }, {}, {},
                    { document = FileTextState() }, {}, {}, backRequest = backRequests)
            }
        } }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("file-text-edit").performClick()
        compose.onNodeWithTag("file-text-input").assertHeightIsAtLeast(320.dp)
        fun findEditor(view: android.view.View = compose.activity.window.decorView): android.widget.EditText? {
            if (view is android.widget.EditText) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findEditor(view.getChildAt(i))?.let { return it }
            return null
        }
        lateinit var editor: android.widget.EditText
        var changes = 0
        lateinit var buffer: android.text.Editable
        compose.runOnIdle {
            editor = checkNotNull(findEditor())
            assertEquals(57 * 1024, editor.text.length)
            assertFalse(editor.isSaveEnabled)
            editor.showSoftInputOnFocus = false
            buffer = editor.text
            editor.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) { changes++ }
            })
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            compose.onNodeWithTag("file-text-input").performTouchInput { swipeUp(durationMillis = 300) }
            compose.runOnIdle {
                assertTrue("Native editor did not scroll", editor.scrollY > 0)
                assertSame("Scrolling replaced the document buffer", buffer, editor.text)
                assertEquals("Scrolling/theme changes rewrote the text", 0, changes)
            }
        }
        var scroll = 0
        compose.runOnIdle { scroll = editor.scrollY }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { findEditor()?.scrollY == scroll }
        compose.runOnIdle {
            editor = checkNotNull(findEditor())
            assertEquals(source, editor.text.toString())
            assertEquals(scroll, editor.scrollY)
            editor.text.append("\n尾部修改")
        }
        compose.runOnIdle { assertTrue(document.draft.endsWith("尾部修改")); assertEquals(0, saves); backRequests++ }
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("file-text-input").assertExists()
        compose.runOnIdle { backRequests++ }
        compose.onNodeWithText("放弃并关闭").performClick()
        compose.onNodeWithTag("file-text-editor").assertDoesNotExist()
    }

    private fun captureText(name: String) {
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        File(directory, "file-text-$name-dark.png").outputStream().use {
            compose.onNodeWithTag("file-text-editor").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

}
