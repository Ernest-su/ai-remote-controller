package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Isolated panels: no stored targets, network connections or device actions. */
class ScheduledFeaturesUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun timersExplainTargetExecutionAndKeepOfflineDraftsInBothThemes() {
        var dark by mutableStateOf(false)
        val empty=ScheduleState()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.5f)) {
                RemoteTheme(darkTheme=dark) {Box(Modifier.width(320.dp).fillMaxHeight()) {
                    SchedulePanel(false,empty,emptyList(),{},{},{_,_,_,_,_,_->error("offline save")},{_,_->},{_,_,_->})
                }}
            }
        }
        for(mode in listOf(false,true)) {
            compose.runOnIdle {dark=mode}
            compose.onNodeWithText("任务保存在被控设备。手机遥控器退出不会影响定时执行。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("新建定时操作").performScrollTo().performClick()
            compose.onNodeWithText("倒计时").performScrollTo().performClick()
            compose.onNodeWithTag("schedule-delay").performScrollTo().performClick()
            captureSchedule("delay-menu-${if (mode) "dark" else "light"}", "schedule-delay-menu")
            compose.onNodeWithTag("schedule-delay-5400").performClick()
            compose.onNodeWithTag("schedule-delay-menu").assertDoesNotExist()
            compose.onNodeWithTag("schedule-delay").assertTextContains("90 分钟")
            compose.onNodeWithTag("schedule-action").performScrollTo().performClick()
            compose.onNodeWithTag("schedule-action-pause").performClick()
            compose.onNodeWithTag("schedule-action-menu").assertDoesNotExist()
            compose.onNodeWithText("保存定时任务").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("每天固定时间").performScrollTo().performClick()
            compose.onNodeWithTag("schedule-time").performScrollTo().performClick()
            captureSchedule("time-picker-${if (mode) "dark" else "light"}", "schedule-time-picker")
            compose.onNodeWithTag("schedule-time-confirm").performClick()
            compose.onNodeWithTag("schedule-time").assertTextContains("每天执行时间：00:00")
            compose.onNodeWithText("保存定时任务").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("收起新建任务").performScrollTo().performClick()
        }
    }
    @Test fun schedulePickersConfirmTargetLocalTimesAndKeepDraftsSeparate() {
        var submitted: List<String?>? = null
        var state by mutableStateOf(ScheduleState(sessionKey = "fixture-session", data = ScheduleList(emptyList(), 0, "2032-02-29 20:30", 28800)))
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { RemoteTheme {
            Box(Modifier.width(320.dp).fillMaxHeight()) {
                SchedulePanel(true, state, emptyList(), {}, {}, { action, pkg, kind, value, session, _ -> submitted = listOf(action, pkg, kind, value, session); state=state.copy(saveRevision=state.saveRevision+1) }, {_,_->}, {_,_,_->})
            }
        } }
        compose.onNodeWithText("新建定时操作").performScrollTo().performClick()
        compose.onNodeWithTag("schedule-delay").performScrollTo().performClick()
        compose.onNodeWithTag("schedule-delay-5400").performClick()
        compose.onNodeWithText("保存定时任务").performScrollTo().performClick()
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle { assertEquals(listOf("pause", null, "delay", "5400", "fixture-session"), submitted) }
        compose.onNodeWithText("新建定时操作").performScrollTo().performClick()
        compose.onNodeWithText("单次").performScrollTo().performClick()
        compose.onNodeWithText("保存定时任务").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("schedule-date").assertDoesNotExist()
        compose.onNodeWithTag("schedule-time").performScrollTo().performClick()
        compose.onNode(hasContentDescription("21", substring = true) and hasClickAction()).performClick()
        compose.onNodeWithText("30").performClick()
        compose.onNode(hasContentDescription("45", substring = true) and hasClickAction()).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("schedule-time-confirm").performClick()
        compose.onNodeWithTag("schedule-time").assertTextContains("21:45", substring = true)
        compose.onNodeWithText("保存定时任务").performScrollTo().performClick()
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle { assertEquals(listOf("pause", null, "once", "2032-02-29 21:45", "fixture-session"), submitted) }
        compose.onNodeWithText("新建定时操作").performScrollTo().performClick()
        compose.onNodeWithText("每天固定时间").performScrollTo().performClick()
        compose.onNodeWithTag("schedule-time").performScrollTo().performClick()
        compose.onNodeWithTag("schedule-time-picker-close").performClick()
        compose.onNodeWithTag("schedule-time").assertTextContains("选择执行时间")
        compose.onNodeWithText("保存定时任务").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("schedule-time").performScrollTo().performClick()
        compose.onNodeWithTag("schedule-time-confirm").performClick()
        compose.onNodeWithText("单次").performScrollTo().performClick()
        compose.onNodeWithTag("schedule-time").performScrollTo().assertTextContains("21:45", substring = true)
        compose.onNodeWithText("每天固定时间").performScrollTo().performClick()
        compose.onNodeWithText("保存定时任务").performScrollTo().performClick()
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle { assertEquals(listOf("pause", null, "daily", "20:30", "fixture-session"), submitted) }
    }
    @Test fun audioSchedulesValidateVolumeKeepDraftAndConfirmExplicitActions() {
        var submitted: Pair<String, Int?>? = null
        var state by mutableStateOf(ScheduleState(sessionKey="fixture"))
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { RemoteTheme { Box(Modifier.width(320.dp).fillMaxHeight()) {
            SchedulePanel(true, state, emptyList(), {}, {},
                { action, _, _, _, _, volume -> submitted = action to volume; state=state.copy(saveRevision=state.saveRevision+1) }, {_,_->}, {_,_,_->})
        } } }
        compose.onNodeWithText("新建定时操作").performScrollTo().performClick()
        compose.onNodeWithTag("schedule-action").performScrollTo().performClick()
        compose.onNodeWithTag("schedule-action-set_volume").performClick()
        for(invalid in listOf("", "-1", "101", "1.5")) {
            compose.onNodeWithTag("schedule-volume").performScrollTo().performTextReplacement(invalid)
            compose.onNodeWithText("保存定时任务").performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithTag("schedule-volume").performScrollTo().performTextReplacement("37")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("schedule-volume").assertTextContains("37")
        compose.onNodeWithText("保存定时任务").performScrollTo().performClick()
        compose.onNodeWithText("媒体音量调至 37%", substring=true).assertExists()
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle { assertEquals("set_volume" to 37, submitted) }
        for(action in listOf("mute", "unmute")) {
            compose.onNodeWithText("新建定时操作").performScrollTo().performClick()
            compose.onNodeWithTag("schedule-action").performScrollTo().performClick()
            compose.onNodeWithTag("schedule-action-$action").performClick()
            compose.onNodeWithTag("schedule-volume").assertDoesNotExist()
            compose.onNodeWithText("保存定时任务").performScrollTo().performClick()
            compose.onNodeWithText("确认").performClick()
            compose.runOnIdle { assertEquals(action to null, submitted) }
        }
    }
    @Test fun customKeysAndBatchSelectionRespectLimits() {
        var downloaded=emptyList<String>()
        compose.setContent { RemoteTheme {Column(Modifier.fillMaxSize().padding(16.dp)) {
            FileBrowserPanel(FileBrowserState(entries=listOf(TargetFileEntry("电影.mp4","file",10)),loaded=true),true,{},{},{},{},{},onDownloadSelected={names,_->downloaded=names})
        }} }
        compose.onNodeWithTag("files-multi-select").performClick()
        compose.onNodeWithTag("files-multi-select-visible").performClick()
        compose.onNodeWithText("下载选中的 1 个文件").performClick()
        compose.onNodeWithTag("batch-mode-confirm").performClick()
        compose.runOnIdle {assertEquals(listOf("电影.mp4"),downloaded)}
    }
    @Test fun launcherReferencesResolveOnlySavedOpaqueTokens() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=context.getSharedPreferences("launcher-actions",0)
        val previous=preferences.getString("items",null)
        val action=LauncherAction(java.util.UUID.randomUUID().toString(),"fixture-tv","app","test.player","电视 · 播放器")
        try {
            preferences.edit().putString("items",kotlinx.serialization.json.Json.encodeToString(listOf(action))).commit()
            assertEquals(action,LauncherActions.resolve(context,LauncherActions.intent(context,action)))
            assertNull(LauncherActions.resolve(context,android.content.Intent(context,MainActivity::class.java).setData(android.net.Uri.parse("airc-action://${context.packageName}/unknown"))))
            assertNull(LauncherActions.resolve(context,android.content.Intent().setData(android.net.Uri.parse("https://${context.packageName}/${action.token}"))))
        } finally {
            preferences.edit().apply {if(previous==null)remove("items")else putString("items",previous)}.commit()
        }
    }

    @Test fun conditionWaitUsesTheAndroidXmlParserWithoutBlindlyContinuing() = kotlinx.coroutines.runBlocking {
        val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Unconfined)
        var actions=0
        val controller=QuickRoutineController(scope) {tool,_->
            if(tool=="device.ui_hierarchy") """{"xml":"<hierarchy><node text=\"A&amp;B\" /></hierarchy>"}"""
            else {actions++;"{}"}
        }
        try {
            assertTrue(controller.start(QuickRoutine("fixture","界面条件",listOf(RoutineStep("wait_text","A&B",1),RoutineStep("app","test.next"))),true))
            kotlinx.coroutines.withTimeout(3000){controller.state.first {!it.busy}}
            assertNull(controller.state.value.error);assertEquals(1,actions)
        } finally {scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()}
    }

    @Test fun pollingKeepsRowsStillAndRunOnceRequiresBoundConfirmation() {
        var state by mutableStateOf(ScheduleState(sessionKey="fixture", data=ScheduleList(
            listOf(ScheduledAction("one", "set_volume", kind="delay", nextAt=1, enabled=false, volumePercent=37)),
            1, "2032-02-29 20:30", 0)))
        var quietRefreshes = 0
        var runs = 0
        var connected by mutableStateOf(true)
        compose.setContent { RemoteTheme { SchedulePanel(connected, state, emptyList(), {}, {},
            {_,_,_,_,_,_->}, {_,_->}, {_,_,_->}, targetName="测试电视",
            runOnce={id, session -> assertEquals("one", id); assertEquals("fixture", session); runs++},
            refreshQuietly={quietRefreshes++}) } }
        compose.onNodeWithTag("schedule-run-one").performScrollTo().assertIsEnabled()
        val before = compose.onNodeWithTag("schedule-run-one").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(10_100)
        compose.runOnIdle { assertEquals(1, quietRefreshes); state=state.copy(data=state.data!!.copy(targetNow=11)) }
        assertEquals(before, compose.onNodeWithTag("schedule-run-one").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("schedule-run-one").performClick()
        compose.onNodeWithText("立即在 测试电视 执行一次：媒体音量调至 37%。原定时计划保持不变。").assertExists()
        compose.runOnIdle { assertEquals(0, runs) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("schedule-run-one").performClick()
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle { assertEquals(1, runs); state=state.copy(data=state.data!!.copy(tasks=state.data!!.tasks.map { it.copy(lastStatus="running") })) }
        compose.onNodeWithTag("schedule-run-one").assertIsNotEnabled()
        compose.runOnIdle { connected=false; state=state.copy(data=state.data!!.copy(tasks=state.data!!.tasks.map { it.copy(lastStatus="submitted") })) }
        compose.onNodeWithTag("schedule-run-one").assertIsNotEnabled()
    }

    @Test fun editsKeepDraftUntilAcceptedAndOfferScreenOff() {
        var state by mutableStateOf(ScheduleState(sessionKey="fixture",data=ScheduleList(listOf(
            ScheduledAction("edit", "set_volume",kind="delay",nextAt=1,enabled=false,delaySeconds=5400,volumePercent=37)),1,"2032-02-29 20:30",0)))
        var submitted:List<String?>?=null
        val restoration=androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { RemoteTheme { Box(Modifier.width(320.dp).fillMaxHeight()) {
            SchedulePanel(true,state,emptyList(),{},{},{_,_,_,_,_,_->error("must update existing task")},{_,_->},{_,_,_->},
                update={id,action,pkg,kind,value,session,_ -> submitted=listOf(id,action,pkg,kind,value,session)})
        } } }
        compose.onNodeWithTag("schedule-edit-edit").performScrollTo().performClick()
        compose.onNodeWithTag("schedule-delay").performScrollTo().assertTextContains("90 分钟")
        compose.onNodeWithTag("schedule-volume").performScrollTo().assertTextContains("37")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("schedule-action").performScrollTo().performClick()
        compose.onNodeWithTag("schedule-action-screen_off").performClick()
        compose.onNodeWithTag("schedule-volume").assertDoesNotExist()
        compose.onNodeWithText("保存修改").performScrollTo().performClick()
        compose.onNodeWithText("替换原定时任务",substring=true).assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertNull(submitted) }
        compose.onNodeWithText("保存修改").performScrollTo().performClick()
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle { assertEquals(listOf("edit","screen_off",null,"delay","5400","fixture"),submitted);state=state.copy(error="fixture rejected") }
        compose.onNodeWithText("保存修改").performScrollTo().assertExists()
        compose.runOnIdle { state=state.copy(saveRevision=1,error=null) }
        compose.onNodeWithTag("schedule-editing").assertDoesNotExist()
        compose.onNodeWithText("新建定时操作").performScrollTo().assertExists()
        compose.runOnIdle { state=state.copy(data=state.data!!.copy(tasks=state.data!!.tasks.map { it.copy(delaySeconds=61) })) }
        compose.onNodeWithTag("schedule-edit-edit").performScrollTo().performClick()
        compose.onNodeWithTag("schedule-delay").performScrollTo().assertTextContains("61 秒", substring=true)
        compose.runOnIdle { state=state.copy(sessionKey="new-connection") }
        compose.onNodeWithTag("schedule-editing").assertDoesNotExist()
        compose.runOnIdle { state=state.copy(data=state.data!!.copy(tasks=state.data!!.tasks.map { it.copy(delaySeconds=null) })) }
        compose.onNodeWithTag("schedule-edit-edit").performScrollTo().performClick()
        compose.onNodeWithText("保存修改").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("schedule-delay").performScrollTo().assertTextContains("请选择倒计时时间")
        compose.onNodeWithTag("schedule-delay").performClick()
        compose.onNodeWithTag("schedule-delay-900").performClick()
        compose.onNodeWithText("保存修改").performScrollTo().assertIsEnabled()
    }

    private fun captureSchedule(name: String, tag: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "schedule-$name.png").outputStream().use {
            compose.onNodeWithTag(tag).assertIsDisplayed().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

}
