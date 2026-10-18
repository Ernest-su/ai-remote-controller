package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class DesktopUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun filePreviewDistinguishesControllerAndTargetPlaybackAndApkInspection() {
        var target="";var apk="";var properties=""
        val entries=listOf(TargetFileEntry("movie.mp4","file",500),TargetFileEntry("app.apk","file",100),TargetFileEntry("notes.txt","file",10))
        compose.setContent {RemoteTheme { FileBrowserPanel(FileBrowserState(entries=entries,loaded=true),true,{},{},{},{},{properties=it.name},
            onMedia={target=it.name},onApk={apk=it.name},previewContent={entry -> androidx.compose.material3.Text("preview:${entry.name}")}) }}
        compose.onNodeWithText("movie.mp4").performScrollTo().performClick()
        compose.onNodeWithTag("files-preview-dialog").assertExists()
        compose.onNodeWithText("在主控设备播放").assertExists()
        assertEquals("",target);assertEquals("",properties)
        compose.onNodeWithText("改为在被控设备播放").performClick()
        assertEquals("movie.mp4",target)
        compose.onNodeWithTag("files-preview-dialog").assertDoesNotExist()
        compose.onNodeWithTag("files-tools-dialog-close").performClick()
        compose.onNodeWithText("app.apk").performScrollTo().performClick()
        assertEquals("app.apk",apk)
        compose.onNodeWithTag("files-tools-dialog").assertExists()
        compose.onNodeWithTag("files-tools-dialog-close").performClick()
        compose.onNodeWithTag("file-more-notes.txt").performScrollTo().performClick()
        compose.onNodeWithText("查看属性").performClick()
        assertEquals("notes.txt",properties)
    }

    @Test fun directoryCreationRequiresValidNameAndAvailableAction() {
        var created:String?=null
        val enabled=androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { RemoteTheme { FileBrowserPanel(FileBrowserState(),true,{},{},{},{},{},onCreateDirectory={created=it},directoryAvailable=enabled.value) } }
        compose.onNodeWithTag("files-create-directory").performClick()
        compose.onNodeWithText("创建目录").assertIsNotEnabled()
        compose.onNodeWithTag("directory-name").performTextInput("../bad")
        compose.onNodeWithText("创建目录").assertIsNotEnabled()
        compose.onNodeWithTag("directory-name").performTextReplacement("new")
        compose.onNodeWithText("创建目录").performClick();assertEquals("new",created)
        compose.runOnIdle{enabled.value=false}
        compose.onNodeWithTag("files-create-directory").assertIsNotEnabled()
    }
    @Test fun mediaControlShowsPositionAndRequiresAvailableSeekTransport() {
        var requested: Long? = null
        val session = MediaSession("com.test.player", true, 2, 1000, 20000, seekSupported = true, sessionTag = "fixture")
        compose.setContent { RemoteTheme { MediaControlPanel(MediaControlState(listOf(session), session.selection(), seekAvailable = true), true, {}, {}, { requested = it }) } }
        compose.onNodeWithText("0:01").assertExists()
        compose.onNodeWithText("0:20").assertExists()
        compose.onNodeWithTag("media-seek-position").performTextInput("0:07")
        compose.onNodeWithText("跳转").performScrollTo().performClick(); assertEquals(7000L, requested)
    }

    @Test fun cacheCleanupConfirmationIsDistinctFromUninstallAndCanBeRejected() {
        val app=TargetAppInfo("com.test.app",0,10001,1,"1","/data/app/test","test",false)
        var accepted:Boolean?=null
        compose.setContent {RemoteTheme {AppCachePanel(AppCacheState(proposal=AppCacheProposal("id","app_cache_clear",app,true,true,"package_manager_cache_only")),true,{accepted=it})}}
        compose.onNodeWithText("确认仅清理应用缓存").assertExists()
        compose.onNodeWithText("卸载并删除数据").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick();assertEquals(false,accepted)
    }
    @Test fun gamepadActionsAreDisabledOffline() {
        compose.setContent { RemoteTheme { androidx.compose.foundation.layout.Column {
            GamepadSessionButton(VirtualGamepadState(), false, { fail("Offline start") }, { fail("Offline stop") })
            GamepadPanel(VirtualGamepadState(), false, { fail("Offline input") })
        } } }
        compose.onNodeWithTag("virtual-button-a").assertIsNotEnabled()
        compose.onNodeWithContentDescription("开始手柄").assertIsNotEnabled()
        compose.onNodeWithText("全部释放并回中").assertDoesNotExist()
        compose.onNodeWithTag("gamepad-advanced").assertDoesNotExist()
    }
    @Test fun screenPreviewIsManualAndCannotStartOffline() {
        compose.setContent { RemoteTheme { PageContent { ScreenPreviewPanel(ScreenPreviewState(),null,false,{ fail("Offline preview started") },{}) } } }
        compose.onNodeWithText("开始屏幕预览").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("停止屏幕预览").assertDoesNotExist()
    }
    @Test fun customPlayerActionsShowOnlyDeclaredNamesAndDisableOfflineSubmission() {
        val session = MediaSession("com.test.player", true, customActions = listOf(MediaCustomAction("quality.hd", "HD")))
        compose.setContent { RemoteTheme { MediaControlPanel(MediaControlState(listOf(session), session.selection(), customActionAvailable = true), false, {}, {}, {}, { fail("Offline action must not be submitted") }) } }
        compose.onNodeWithText("HD").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("选集").assertDoesNotExist()
    }
    @Test fun storageAnalysisShowsIncompleteReportAndDoesNotOfferImplicitCleaning() {
        var analyzed = false
        val report = StorageReport("inbox", "folder", StorageTotals(1, 7, 4096), mapOf("images" to StorageTotals(1, 7, 4096)), listOf(StorageLargeFile("folder/a.jpg", "images", 7, 4096)), 1, 1, 0, 0, 0, 0, 0, 0, emptyList(), 0, true, false, false)
        compose.setContent { RemoteTheme { Column { StorageAnalysisPanel(StorageAnalysisState(root = "inbox", path = "folder", report = report), false, { analyzed = true }, {}) } } }
        compose.onNodeWithText("分析此目录").assertIsNotEnabled()
        compose.onNodeWithText("报告不完整：达到扫描上限、存在访问错误或目录发生变化。").assertExists()
        compose.onNodeWithText("folder/a.jpg").assertExists()
        compose.onNodeWithText("删除").assertDoesNotExist()
        assertFalse(analyzed)
    }

    @Test fun storageScanShowsItemCountAndCancellation() {
        var cancelled = false
        compose.setContent { RemoteTheme { Column { StorageAnalysisPanel(StorageAnalysisState(busy = true, root = "inbox", path = "folder", phase = "scanning", visited = 42, limit = 100), true, {}, { cancelled = true }) } } }
        compose.onNodeWithText("已扫描 42 项，最多 100 项").assertExists()
        compose.onNodeWithText("分析此目录").assertIsNotEnabled()
        compose.onNodeWithText("取消存储分析").performClick(); assertTrue(cancelled)
    }

    @Test fun apkReplacementConfirmationShowsVersionAndCrossUserImpactAndAllowsRejection() {
        var accepted: Boolean? = null
        val apk = TargetApkInfo("inbox", "fixture.apk", "com.test.app", 2, "new", sizeBytes = 10, sha256 = "a".repeat(64), signatures = emptyList(), signatureVerified = false)
        val old = TargetAppInfo("com.test.app", 0, 10123, 1, "old", "/data/app/old", "fixture-time", false)
        val proposal = AppInstallProposal("fixture", "app_install", apk, old, 0, true, true, true)
        compose.setContent { RemoteTheme { AppInstallationPanel(AppInstallationState(busy = true, proposal = proposal, phase = "awaiting_approval"), null, true, {}, { accepted = it }, {}) } }
        compose.onNodeWithText("com.test.app").assertExists()
        compose.onNodeWithText("old (1)").assertExists()
        compose.onNodeWithText("new (2)").assertExists()
        compose.onNodeWithText("替换 APK 程序代码可能影响该包的所有 Android 用户；应用升级迁移也可能改变原有数据。").assertExists()
        assertNull(accepted)
        compose.onNodeWithText("取消").performClick(); assertEquals(false, accepted)
    }

    @Test fun applicationListPaginatesAndSearchesBeyondFirstPage() {
        val apps=(1..31).map { InstalledApp("test.app$it","App $it",false) }
        compose.setContent { RemoteTheme { ApplicationsPanel(apps,true,false,null,"",{},{}) } }
        compose.onNodeWithTag("app-open-test.app31").assertDoesNotExist()
        compose.onNodeWithTag("apps-page").performScrollToNode(hasText("显示更多应用"))
        compose.onNodeWithText("显示更多应用").performClick()
        compose.onNodeWithTag("apps-page").performScrollToNode(hasTestTag("app-open-test.app31"))
        compose.onNodeWithTag("app-open-test.app31").assertExists()
        compose.onNodeWithTag("apps-page").performScrollToNode(hasTestTag("apps-search"))
        compose.onNodeWithTag("apps-search").performTextInput("test.app31")
        compose.onNodeWithTag("app-open-test.app31").assertExists()
        compose.onNodeWithTag("app-open-test.app1").assertDoesNotExist()
    }

    @Test fun splitSelectionRequiresBaseAndAllowsRemoval() {
        val base=TargetApkInfo("inbox","base.apk","test.app",1,sizeBytes=10,sha256="a".repeat(64),signatures=emptyList(),signatureVerified=false)
        val split=base.copy(path="split.apk",split="config.en",sha256="b".repeat(64))
        val selected=androidx.compose.runtime.mutableStateOf(listOf(split))
        var prepared=false
        compose.setContent { RemoteTheme { PageContent {
            AppInstallationPanel(AppInstallationState(selectedApks=selected.value),null,true,{}, {}, {},
                onRemove={ selected.value=selected.value-it },onPrepareSet={prepared=true})
        } } }
        compose.onNodeWithText("准备安装整组 APK").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { selected.value=listOf(base,split) }
        compose.onNodeWithText("准备安装整组 APK").performScrollTo().assertIsEnabled().performClick()
        assertTrue(prepared)
        compose.onAllNodesWithText("移除此 APK")[0].performScrollTo().performClick()
        compose.onNodeWithText("准备安装整组 APK").performScrollTo().assertIsNotEnabled()
    }

    @Test fun apkPreparationRequiresExplicitReplacementSelection() {
        var update: Boolean? = null
        val apk = TargetApkInfo("inbox", "fixture.apk", "com.test.app", 2, sizeBytes = 10, sha256 = "a".repeat(64), signatures = emptyList(), signatureVerified = false)
        compose.setContent { RemoteTheme { Column { AppInstallationPanel(AppInstallationState(), apk, true, { update = it }, {}, {}) } } }
        compose.onNodeWithText("准备安装此 APK").performClick(); assertEquals(false, update)
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("准备安装此 APK").performClick(); assertEquals(true, update)
    }

    @Test fun apkInspectionShowsByteProgressAndOffersCancellation() {
        var cancelled = false
        compose.setContent { RemoteTheme { Column { ApkInspectionPanel(ApkInspectionState(busy = true, phase = "inspecting", bytes = 5, total = 10), { cancelled = true }) } } }
        compose.onNodeWithText("5 / 10 字节").assertExists()
        compose.onNodeWithText("取消 APK 解析").performClick()
        assertTrue(cancelled)
    }

    @Test fun apkInformationShowsDigestAndDoesNotClaimSignatureVerification() {
        val info = TargetApkInfo("inbox", "fixture.apk", "com.test.app", 42, "fixture", sizeBytes = 10, sha256 = "a".repeat(64), signatures = listOf(ApkSignatureInfo("v2", listOf("b".repeat(64)), true)), signatureVerified = false)
        compose.setContent { RemoteTheme { Column { ApkInspectionPanel(ApkInspectionState(info = info)) } } }
        compose.onNodeWithText("com.test.app").assertExists()
        compose.onNodeWithText("a".repeat(64)).assertExists()
        compose.onNodeWithText("证书指纹仅从 APK 声明读取，此步骤未验证签名真伪或文件可安装性。").assertExists()
    }

    @Test fun uninstallConfirmationNamesPackageAndAllowsRejection() {
        var accepted: Boolean? = null
        val info = TargetAppInfo("ernest.airc.mediafixture", 0, 10123, 1, "fixture", "/data/app/fixture", "fixture-time", false)
        compose.setContent { RemoteTheme { AppManagementPanel(AppManagementState(proposal = AppUninstallProposal("fixture", "app_uninstall", info, true, true)), true, { accepted = it }) } }
        compose.onNodeWithText("ernest.airc.mediafixture").assertExists()
        assertNull(accepted)
        compose.onNodeWithText("取消").performClick()
        assertEquals(false, accepted)
    }

    @Test fun fullRootRequiresConfirmationAndDeleteDialogShowsExactTarget() {
        var root=""
        compose.setContent { RemoteTheme { FileBrowserPanel(FileBrowserState(),true,{},{},{},{},{},onRoot={root=it}) } }
        compose.onNodeWithText("全盘 /").performClick()
        assertEquals("",root)
        compose.onNodeWithText("进入全盘浏览").assertExists()
        compose.onNodeWithText("进入").performClick()
        assertEquals("full",root)
    }
    @Test fun deletionConfirmationCanBeRejectedWithoutExecuting() {
        var accepted:Boolean?=null
        compose.setContent { RemoteTheme { Column { FileTransferPanel(
            FileTransferState(running=true,phase="awaiting_approval",proposal=FileActionProposal("fixture","delete","inbox","folder",true,true,directory=true)),
            "inbox","",true,{},{},{},{},onConfirm={accepted=it}) } } }
        compose.onNodeWithText("确认删除").assertExists()
        compose.onNodeWithText("服务收件箱/folder").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals(false,accepted)
    }
    @Test fun overwriteRequiresExplicitSelectionAndDoesNotUseOrdinaryUpload() {
        var ordinary=false;var overwrite=""
        compose.setContent { RemoteTheme { Column { FileTransferPanel(FileTransferState(sourceName="file.bin"),"inbox","",true,{},{ordinary=true},{},{},onOverwrite={overwrite=it}) } } }
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("推送选中文件").performClick()
        assertFalse(ordinary);assertEquals("file.bin",overwrite)
    }

    @Test fun recoveredCopyPanelShowsPathAndExplicitRelease() {
        var id=""
        compose.setContent{RemoteTheme{Column{FileMediaPanel(FileMediaState(retainedCopies=listOf(MediaPublication("fixture","downloads",".airc-media-fixture/picture.png",10,"sha"))),true,{},{},{},onRecover={},onReleaseCopy={id=it})}}}
        compose.onNodeWithText("Downloads/.airc-media-fixture/picture.png").assertExists()
        compose.onNodeWithText("释放此副本").performClick()
        assertEquals("fixture",id)
    }
    @Test fun localPlaybackCopyRequiresDisclosureAndExplicitConfirmation() {
        var opened=false
        compose.setContent {RemoteTheme {Column {FileMediaPanel(FileMediaState(players=TargetMediaPlayers("inbox","picture.png","image/png","local-copy",listOf("com.test.viewer"),100),selectedPackage="com.test.viewer",localCopy=true),true,{},{opened=true},{})}}}
        compose.onNodeWithText("创建副本并用目标播放器打开").performClick()
        assertFalse(opened)
        compose.onNodeWithText("创建本地播放副本").assertExists()
        compose.onNodeWithText("创建并打开").performClick()
        assertTrue(opened)
    }
    @Test fun mediaPanelRequiresAPlayerAndReportsOnlyLaunch() {
        var opened=false
        compose.setContent {RemoteTheme {Column {FileMediaPanel(FileMediaState(players=TargetMediaPlayers("inbox","picture.png","image/png","http",emptyList())),true,{},{opened=true},{})}}}
        compose.onNodeWithText("用目标播放器打开").assertIsNotEnabled()
        assertFalse(opened)
    }

    @Test fun largeTransferPanelShowsTargetProgressAndCancellation() {
        var cancelled=false
        compose.setContent { RemoteTheme { androidx.compose.foundation.layout.Column { FileTransferPanel(
            FileTransferState(sourceName="large.bin",running=true,phase="uploading",bytes=17000000,total=34000000,root="inbox",path="large.bin"),
            "inbox","",true,{}, {}, {cancelled=true}, {}) } } }
        compose.onNodeWithText("正在推送").assertExists()
        compose.onNodeWithText("17000000 / 34000000 字节").assertExists()
        compose.onNodeWithText("选择本地文件").assertIsNotEnabled()
        compose.onNodeWithText("取消上传").performClick()
        assertTrue(cancelled)
    }

    @Test fun fileBrowserOffersDirectoryAndMetadataActionsWithoutFollowingLinks() {
        var entered = ""
        var inspected = ""
        val folder = TargetFileEntry("folder", "directory")
        val file = TargetFileEntry("test.txt", "file", 3)
        compose.setContent { RemoteTheme { FileBrowserPanel(FileBrowserState(entries = listOf(folder, file, TargetFileEntry("escape", "symlink")), loaded = true), true, {}, {}, {}, { entered = it.name }, { inspected = it.name }) } }
        compose.onNodeWithText("folder").performScrollTo().performClick()
        assertEquals("folder", entered)
        compose.onNodeWithText("test.txt").performScrollTo().performClick()
        assertEquals("test.txt", inspected)
        compose.onNodeWithTag("files-properties-dialog-close").performClick()
        compose.onNodeWithText("符号链接 · 不可访问").performScrollTo().assertIsDisplayed()
    }

    @Test fun diagnosticPanelDistinguishesSkippedStagesAndReportsDuration() {
        compose.setContent { RemoteTheme { NetworkDiagnosticsPanel(DiagnosticState(stages = listOf(
            DiagnosticStage("runtime HTTP", DiagnosticOutcome.PASSED, 12, "协议通过"),
            DiagnosticStage("mDNS", DiagnosticOutcome.SKIPPED, null, "未提供探测"))), true, {}, {}) } }
        compose.onNodeWithText("runtime HTTP · 通过 · 12ms").assertExists()
        compose.onNodeWithText("mDNS · 未检查").assertExists()
        compose.onNodeWithText("诊断服务连接").assertIsEnabled()
    }

    @Test fun screenshotPreviewAndOfflineStateExposeOnlyAvailableActions() {
        var saves = 0
        compose.setContent { RemoteTheme { ScreenshotPanel(androidx.compose.ui.graphics.ImageBitmap(1, 1), false, false, null, {}, { saves++ }) } }
        compose.onNodeWithTag("screenshot-preview").assertExists()
        compose.onNodeWithText("获取 / 刷新截图").assertIsNotEnabled()
        compose.onNodeWithText("保存 PNG").performClick()
        assertEquals(1, saves)
    }

    @Test fun deviceNameEditorRejectsBlankAndSavesChosenName() {
        var saved = ""
        compose.setContent { RemoteTheme { DeviceNameEditor("uuid", "电视", true) { saved = it } } }
        compose.onNodeWithText("修改显示名").performClick()
        compose.onNodeWithText("电视").performTextClearance()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("客厅电视")
        compose.onNodeWithText("保存").performClick()
        assertEquals("客厅电视", saved)
        compose.onNodeWithText("设备显示名").assertDoesNotExist()
    }

    @Test fun phoneProportionsNavigationOfflineActionsAndModelCacheWorkOnJvm() {
        val directory = Files.createTempDirectory("airc-desktop-ui-")
        val controller = DesktopController(store = DesktopProfileStore(directory), catalog = ModelCatalog { listOf("Model-A", "Model-B") })
        try {
            compose.setContent { Box(Modifier.size(390.dp, 844.dp)) { DesktopApp(controller) } }
            compose.onNodeWithTag("remote-page").assertIsDisplayed()
            compose.onNodeWithTag("remote-send-command").assertDoesNotExist()
            compose.onNodeWithTag("remote-model-required").assertIsDisplayed().assertHasNoClickAction()
            compose.onNodeWithTag("device-status").performClick()
            compose.onNodeWithTag("devices-page").assertIsDisplayed()
            compose.onNodeWithTag("navigate-back").performClick()
            compose.onNodeWithTag("open-menu").performClick()
            compose.onNodeWithTag("feature-keypad").performScrollTo().performClick()
            compose.onNodeWithTag("keypad-1").assertIsNotEnabled()
            compose.onNodeWithTag("navigate-back").performClick()
            for ((feature, page) in listOf("apps" to "apps-page", "service" to "service-page",
                "permissions" to "permissions-page", "device_info" to "device-info-page", "results" to "results-page",
                "help" to "help-page", "screenshot" to "screenshot-page", "files" to "files-page", "touchpad" to "touchpad-page", "settings" to "settings-page")) {
                compose.onNodeWithTag("feature-$feature").performScrollTo().performClick()
                compose.onNodeWithTag(page).assertIsDisplayed()
                if(feature=="screenshot") {
                    compose.onNodeWithText("获取 / 刷新截图").assertIsNotEnabled()
                    compose.onNodeWithText("开始屏幕镜像").assertDoesNotExist()
                    compose.onNodeWithText("开始屏幕预览").assertDoesNotExist()
                    compose.onNodeWithTag("screen-mirror-image").assertDoesNotExist()
                }
                compose.onNodeWithTag("navigate-back").performClick()
            }
            compose.onNodeWithTag("feature-model").performScrollTo().performClick()
            compose.onNodeWithTag("model-api-url").performScrollTo().performTextInput("https://api.test/v1")
            compose.onNodeWithTag("model-api-key").performScrollTo().performTextInput("fixture-key")
            compose.onNodeWithTag("fetch-models").performScrollTo().performClick()
            compose.waitUntil(5000) { controller.profiles.state.value.draft.models.size == 2 }
            compose.onNodeWithText("从缓存列表选择模型").performScrollTo().performClick()
            compose.onNodeWithText("Model-B").performClick()
            assertEquals("Model-B", controller.profiles.state.value.draft.model)
            compose.onNodeWithText("更新到目标设备").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("navigate-back").performClick()
            compose.onNodeWithTag("feature-text").performScrollTo().performClick()
            compose.onNodeWithTag("text-command-input").performTextInput("保留草稿")
            compose.onNodeWithTag("device-status").performClick()
            compose.onNodeWithTag("navigate-back").performClick()
            compose.onNodeWithText("保留草稿").assertIsDisplayed()
        } finally { controller.close(); directory.toFile().deleteRecursively() }
    }
}
