package ernest.airc

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Isolated fixtures: narrow window, large text and both themes, without target actions. */
class LibraryLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun librariesAndStepEditorSupportNarrowLargeTextInBothThemes() {
        val apps = listOf(InstalledApp("fixture.player", "客厅电视视频播放器", false), InstalledApp("fixture.settings", "电视系统设置", true))
        var dark by mutableStateOf(false)
        var screen by mutableStateOf("apps")
        var kind by mutableStateOf("key")
        var value by mutableStateOf("ok")
        var favorites by mutableStateOf(listOf("fixture.player"))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
                RemoteTheme(darkTheme = dark) {
                    Box(Modifier.width(320.dp).height(640.dp).background(RemoteColors.Background)) {
                        when (screen) {
                            "apps" -> ApplicationLibraryPanel(apps, favorites, true, false, null, {}, {},
                                onFavorite = { pkg -> favorites = if (pkg in favorites) favorites - pkg else favorites + pkg })
                            "saved" -> RoutineLibraryContent(listOf(QuickRoutine("movie", "开始客厅电视观影", listOf(RoutineStep("app", "fixture.player"), RoutineStep("wait", "1500")))), apps, true, RoutineRunState(), emptyMap(), {}, {}, {}, {}, {}, {}, true)
                            "routines" -> RoutineEditorContent("下班后开始观影", {}, listOf(RoutineStep("app", "fixture.player"), RoutineStep("wait", "1500"), RoutineStep("key", "play_pause")), apps, true, {}, {}, { _, _ -> }, {}, {}, {})
                            "details" -> RoutineExecutionDetails(RoutineExecution(QuickRoutine("failed", "客厅电视的快捷操作", listOf(RoutineStep("key", "ok"))),
                                targetName = "客厅电视", startedAt = 1, finishedAt = 2, status = RoutineStatus.FAILED,
                                steps = listOf(RoutineStepExecution(RoutineStepStatus.FAILED, startedAt = 1, finishedAt = 2, error = "按键释放未确认，请核对电视画面。", output = "fixture-receipt"))), apps, false, {}, {})
                            else -> RoutineStepEditorContent(kind, { kind = it; value = "" }, value, { value = it }, apps, true, {}, true, {})
                        }
                    }
                }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode; screen = "apps" }
            compose.onNodeWithTag("app-favorite-fixture.player").performScrollTo().assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            capture("apps", mode)
            compose.onNodeWithTag("library-filter-system").performScrollTo().performClick()
            compose.onNodeWithText("电视系统设置").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("客厅电视视频播放器").assertDoesNotExist()
            compose.onNodeWithTag("app-favorite-fixture.settings").performClick().assertIsOn()
            compose.onNodeWithTag("app-favorite-fixture.settings").performClick().assertIsOff()
            compose.runOnIdle { screen = "saved" }
            compose.onNodeWithContentDescription("运行 开始客厅电视观影").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
            capture("routine-list", mode)
            compose.runOnIdle { screen = "routines" }
            compose.onNodeWithText("添加步骤").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("routine-step-type-2", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            capture("routine-editor", mode)
            compose.onNodeWithTag("routine-step-menu-0").performScrollTo().performClick()
            compose.onNodeWithText("编辑步骤").assertIsDisplayed()
            compose.onNodeWithText("编辑步骤").performClick()
            compose.runOnIdle { screen = "step"; kind = "key"; value = "ok" }
            compose.onNodeWithTag("routine-key-ok").performScrollTo().assertIsDisplayed().assertIsSelected()
            capture("routine-keys", mode)
            compose.onNodeWithTag("routine-kind-selector").performScrollTo().performClick()
            compose.onNodeWithTag("routine-kind-wait").performClick()
            compose.onNodeWithText("2 秒").performScrollTo().performClick()
            compose.onNodeWithTag("routine-kind-selector").performScrollTo().performClick()
            compose.onNodeWithTag("routine-kind-wait").performClick()
            compose.onNodeWithTag("routine-wait-seconds").assertTextContains("2")
            capture("routine-wait", mode)
            compose.onNodeWithTag("routine-kind-selector").performScrollTo().performClick()
            compose.onNodeWithTag("routine-kind-wait_app").assertTextContains("等待应用进入前台").performClick()
            compose.onNodeWithTag("routine-kind-menu").assertDoesNotExist()
            compose.onAllNodesWithText("等待应用进入前台").assertCountEquals(2)
            compose.onNodeWithText("在电视上打开一个应用").assertDoesNotExist()
            capture("routine-wait-foreground", mode)
            compose.onNodeWithTag("routine-kind-selector").performScrollTo().performClick()
            compose.onNodeWithTag("routine-kind-wait_playing").assertTextContains("等待播放开始").performClick()
            compose.onNodeWithTag("routine-kind-selector").assertTextContains("等待播放开始")
            capture("routine-wait-playing", mode)
            compose.onNodeWithTag("routine-kind-selector").performScrollTo().performClick()
            compose.onNodeWithTag("routine-kind-app").performClick()
            compose.onNodeWithText("在电视上打开一个应用").assertIsDisplayed()
            compose.runOnIdle { screen = "details" }
            compose.onNodeWithText("查看返回详情").performScrollTo().performClick()
            compose.onNodeWithText("fixture-receipt").performScrollTo().assertIsDisplayed()
            capture("routine-details", mode)
        }
    }

    private fun capture(name: String, dark: Boolean) {
        val output = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "library-$name-${if (dark) "dark" else "light"}.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
