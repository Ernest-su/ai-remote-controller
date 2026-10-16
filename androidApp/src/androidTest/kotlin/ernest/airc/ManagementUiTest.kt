package ernest.airc

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI fixtures only: callbacks do not modify any target device. */
class ManagementUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val entries = listOf(TargetFileEntry("notes.txt", "file", 24), TargetFileEntry("Photos", "directory"),
        TargetFileEntry("movie.mp4", "file", 7_340_032), TargetFileEntry("shortcut", "symlink"))
    private val apps = listOf(InstalledApp("example.music", "音乐", false), InstalledApp("example.settings", "设置", true))
    private fun screenshot(name: String) {
        val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "$name.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun gridAndCategoryFilterOnlyLoadedEntriesWithoutRequests() {
        var requests=0
        compose.setContent { RemoteTheme { FileBrowserPanel(FileBrowserState(entries=entries,loaded=true,nextCursor="shortcut"),true,
            {requests++},{},{requests++},{},{}) } }
        compose.onNodeWithTag("files-view").performClick()
        compose.onNodeWithTag("files-page").performScrollToNode(hasTestTag("files-category"))
        compose.onNodeWithTag("files-category").performClick()
        compose.onNodeWithTag("files-category-video").performClick()
        compose.onNodeWithTag("files-page").performScrollToNode(hasTestTag("file-row-movie.mp4"))
        compose.onNodeWithTag("file-row-movie.mp4").assertIsDisplayed()
        compose.onNodeWithTag("file-row-Photos").assertDoesNotExist()
        compose.onNodeWithTag("files-view").assertContentDescriptionEquals("切换列表视图")
        assertEquals(0,requests)
        compose.onNodeWithTag("files-page").performScrollToNode(hasText("加载下一页"))
        compose.onNodeWithText("加载下一页").performClick()
        assertEquals(1,requests)
    }

    @Test fun longPressSelectsWithoutSubmittingAndContextActionTargetsSelectedFile() {
        var deleted="";var inspected=""
        compose.setContent { RemoteTheme { FileBrowserPanel(FileBrowserState(entries=entries,loaded=true),true,{},{},{},{},{inspected=it.name},onDelete={deleted=it.name}) } }
        compose.onNodeWithTag("files-page").performScrollToNode(hasTestTag("file-row-notes.txt"))
        compose.onNodeWithText("notes.txt").performTouchInput { longClick() }
        compose.onNodeWithTag("files-clear-selection").assertIsDisplayed()
        assertEquals("",deleted);assertEquals("",inspected)
        compose.onNodeWithText("删除").performClick()
        assertEquals("notes.txt",deleted)
        compose.onNodeWithTag("files-tools-dialog-close").performClick()
        compose.onNodeWithTag("files-clear-selection").performClick()
        compose.onNodeWithContentDescription("上传文件").assertIsDisplayed()
    }

    @Test fun hiddenFilesAndGridPreferenceSurviveRestoration() {
        val restoration=StateRestorationTester(compose)
        restoration.setContent { RemoteTheme { FileBrowserPanel(FileBrowserState(entries=entries+TargetFileEntry(".config","file",10),loaded=true),true,{},{},{},{},{}) } }
        compose.onNodeWithTag("file-row-.config").assertDoesNotExist()
        compose.onNodeWithTag("files-view").performClick()
        compose.onNodeWithTag("files-page").performScrollToNode(hasTestTag("files-category"))
        compose.onNodeWithTag("files-category").performClick()
        compose.onNodeWithText("显示隐藏文件").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("files-view").assertContentDescriptionEquals("切换列表视图")
        compose.onNodeWithTag("files-page").performScrollToNode(hasTestTag("file-row-.config"))
        compose.onNodeWithTag("file-row-.config").assertIsDisplayed()
    }

    @Test fun fileListPrioritizesFoldersAndKeepsActionsInMenu() {
        var entered = ""; var deleted = ""
        compose.setContent { RemoteTheme { Surface(color = RemoteColors.Background) {
            FileBrowserPanel(FileBrowserState(entries = entries, loaded = true), true, {}, {}, {}, { entered = it.name }, {},
                onDelete = { deleted = it.name })
        } } }
        compose.onNodeWithTag("files-page").performScrollToNode(hasTestTag("file-row-Photos"))
        val folder = compose.onNodeWithTag("file-row-Photos").fetchSemanticsNode().positionInRoot.y
        val movie = compose.onNodeWithTag("file-row-movie.mp4").fetchSemanticsNode().positionInRoot.y
        assertTrue(folder < movie)
        compose.onNodeWithText("Photos").performClick(); assertEquals("Photos", entered)
        compose.onNodeWithText("删除").assertDoesNotExist()
        compose.onNodeWithTag("file-more-shortcut").assertDoesNotExist()
        compose.onNodeWithTag("file-more-movie.mp4").performClick()
        assertEquals("", deleted)
        compose.onNodeWithText("删除").performClick(); assertEquals("movie.mp4", deleted)
        compose.onNodeWithTag("files-tools-dialog").assertIsDisplayed()
    }

    @Test fun fileSearchAndSortDoNotFetchOrLosePaging() {
        var requests = 0
        compose.setContent { RemoteTheme { FileBrowserPanel(FileBrowserState(entries = entries, loaded = true, nextCursor = "shortcut"), true,
            { requests++ }, {}, { requests++ }, {}, {}) } }
        compose.onNodeWithTag("files-search").performTextInput("movie")
        compose.onNodeWithTag("files-page").performScrollToNode(hasTestTag("file-row-movie.mp4"))
        compose.onNodeWithTag("file-row-Photos").assertDoesNotExist()
        compose.onNodeWithTag("files-page").performScrollToNode(hasTestTag("files-sort"))
        compose.onNodeWithTag("files-sort").performClick()
        compose.onNodeWithText("大小从大到小").performClick()
        assertEquals(0, requests)
        compose.onNodeWithTag("files-page").performScrollToNode(hasText("加载下一页"))
        compose.onNodeWithText("加载下一页").performClick(); assertEquals(1, requests)
    }

    @Test fun directoryDraftSurvivesClosingAndStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var created = ""
        restoration.setContent { RemoteTheme { FileBrowserPanel(FileBrowserState(loaded = true), true, {}, {}, {}, {}, {}, onCreateDirectory = { created = it }) } }
        compose.onNodeWithTag("files-create-directory").performClick()
        compose.onNodeWithTag("directory-name").performTextInput("New Folder")
        compose.onNodeWithTag("files-directory-dialog-close").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("files-create-directory").performClick()
        compose.onNodeWithTag("directory-name").assertTextContains("New Folder")
        compose.onNodeWithText("创建目录").performClick()
        assertEquals("New Folder", created)
        compose.onNodeWithTag("files-directory-dialog").assertDoesNotExist()
    }

    @Test fun breadcrumbAndFullRootRequireExplicitActions() {
        var path = "unset"; var root = "downloads"
        var location by mutableStateOf("one/two")
        compose.setContent { RemoteTheme { Box(Modifier.width(320.dp)) {
            FileBrowserPanel(FileBrowserState(path = location, loaded = true), true, {}, {}, {}, {}, {},
                onRoot = { root = it }, onNavigate = { path = it })
        } } }
        compose.onNodeWithTag("files-path-0").performClick(); assertEquals("one", path)
        compose.onNodeWithTag("files-root-full").performClick(); assertEquals("downloads", root)
        compose.onNodeWithText("取消").performClick(); assertEquals("downloads", root)
        compose.onNodeWithTag("files-root-full").performClick()
        compose.onNodeWithText("进入").performClick(); assertEquals("full", root)
        compose.runOnIdle { location="one/long-folder-name/two/another-long-folder/current" }
        compose.onNodeWithTag("files-path-4").performScrollTo().assertIsDisplayed()
        val last=compose.onNodeWithTag("files-path-4").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("files-path-0").performScrollTo().assertIsDisplayed().performClick()
        val first=compose.onNodeWithTag("files-path-0").getUnclippedBoundsInRoot()
        assertEquals(first.top.value,last.top.value,1f)
        assertEquals((first.bottom-first.top).value,(last.bottom-last.top).value,1f)
        assertEquals("one",path)
    }

    @Test fun offlineFileActionsAreDisabledAndCachedRowsRemainReadable() {
        var calls = 0
        compose.setContent { RemoteTheme { FileBrowserPanel(FileBrowserState(entries = entries, loaded = true), false, { calls++ }, {}, {}, { calls++ }, { calls++ }, onDelete = { calls++ }) } }
        compose.onNodeWithTag("files-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("files-page").performScrollToNode(hasTestTag("file-row-movie.mp4"))
        compose.onNodeWithTag("file-more-movie.mp4").performClick()
        compose.onNodeWithText("删除").assertIsNotEnabled()
        compose.onNodeWithText("查看属性").assertIsNotEnabled()
        assertEquals(0, calls)
    }

    @Test fun applicationSearchIncludesUnexpandedItemsAndRestoresFilter() {
        val restoration = StateRestorationTester(compose)
        val many = (1..65).map { InstalledApp("example.app$it", "应用 $it", it == 65) }
        restoration.setContent { RemoteTheme { ApplicationsPanel(many, true, false, null, "", {}, {}) } }
        compose.onNodeWithTag("apps-search").performTextInput("app65")
        compose.onNodeWithTag("app-row-example.app65").assertIsDisplayed()
        compose.onNodeWithTag("apps-filter-user").performClick()
        compose.onNodeWithText("没有匹配的应用").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("apps-search").assertTextContains("app65")
        compose.onNodeWithTag("apps-filter-user").assertIsSelected()
        compose.onNodeWithText("清除筛选").performClick()
        compose.onNodeWithTag("app-row-example.app1").assertIsDisplayed()
    }

    @Test fun appMenusRespectSystemBoundaryAndOfflineState() {
        var removed = ""; var launched = ""
        val connected = mutableStateOf(true)
        compose.setContent { RemoteTheme { ApplicationsPanel(apps, connected.value, false, null, "", {}, { launched = it }, onUninstall = { removed = it }) } }
        compose.onNodeWithTag("app-open-example.music").performClick(); assertEquals("example.music", launched)
        compose.onNodeWithTag("apps-page").performScrollToNode(hasTestTag("app-more-example.settings"))
        compose.onNodeWithTag("app-more-example.settings").performClick()
        compose.onNodeWithText("卸载").assertDoesNotExist()
        compose.onNodeWithText("应用详情").performClick()
        compose.onNodeWithTag("app-detail-dialog-close").performClick()
        compose.onNodeWithTag("app-more-example.music").performClick()
        assertEquals("", removed)
        compose.onNodeWithText("卸载").performClick(); assertEquals("example.music", removed)
        compose.onNodeWithTag("app-management-dialog-close").performClick()
        compose.runOnIdle { connected.value = false }
        compose.onNodeWithTag("apps-page").performScrollToNode(hasTestTag("app-open-example.music"))
        compose.onNodeWithTag("app-open-example.music").assertIsNotEnabled()
        compose.onNodeWithTag("app-more-example.music").performClick()
        compose.onNodeWithText("卸载").assertIsNotEnabled()
    }

    @Test fun closeToolsRetainsTransferDraftAndTaskEntry() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RemoteTheme {
            FileBrowserPanel(FileBrowserState(loaded = true), true, {}, {}, {}, {}, {}, operationMessage = "文件已下载，可保存到本机", transfer = {
                FileTransferPanel(FileTransferState(sourceName = "source.txt"), "downloads", "", true, {}, {}, {}, {})
            })
        } }
        compose.onNodeWithTag("files-tools").performClick()
        compose.onNodeWithText("目标文件名").performScrollTo().performTextReplacement("renamed.txt")
        compose.onNodeWithTag("files-tools-dialog-close").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("files-tools").performClick()
        compose.onNodeWithText("目标文件名").performScrollTo().assertTextContains("renamed.txt")
    }

    @Test fun managementScreenshotsAndLargeTextRemainUsable() {
        val dark = mutableStateOf(false)
        val files = mutableStateOf(false)
        val large = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large.value) 1.6f else 1f)) {
                RemoteTheme(dark.value) { Surface(color = RemoteColors.Background, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.widthIn(max = if (large.value) 320.dp else 360.dp)) {
                        Text(if (files.value) "文件管理" else "应用管理", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                        if (files.value) FileBrowserPanel(FileBrowserState(entries = entries, loaded = true), true, {}, {}, {}, {}, {}, onRoot = {}, onCreateDirectory = {})
                        else ApplicationsPanel(apps, true, false, null, "", {}, {}, onInspect = {})
                    }
                } }
            }
        }
        screenshot("management-apps")
        compose.runOnIdle { large.value = true }
        compose.onNodeWithTag("apps-page").performScrollToNode(hasTestTag("app-more-example.music"))
        compose.onNodeWithTag("app-more-example.music").performClick()
        compose.onNodeWithText("应用详情").assertIsDisplayed().performClick()
        compose.onNodeWithTag("app-detail-dialog-close").assertIsDisplayed().performClick()
        screenshot("management-apps-large")
        compose.runOnIdle { files.value = true; large.value = false }
        screenshot("management-files")
        compose.runOnIdle { large.value = true }
        compose.onNodeWithTag("files-page").performScrollToNode(hasTestTag("file-row-movie.mp4"))
        compose.onNodeWithTag("file-more-movie.mp4").performClick()
        compose.onNodeWithText("查看属性").assertIsDisplayed().performClick()
        compose.onNodeWithTag("files-properties-dialog-close").assertIsDisplayed().performClick()
        screenshot("management-files-large")
        compose.onNodeWithTag("files-view").performClick()
        compose.onNodeWithTag("files-page").performScrollToNode(hasTestTag("file-row-movie.mp4"))
        screenshot("management-files-grid-large")
        compose.runOnIdle { dark.value=true }
        screenshot("management-files-grid-large-dark")
    }
}
