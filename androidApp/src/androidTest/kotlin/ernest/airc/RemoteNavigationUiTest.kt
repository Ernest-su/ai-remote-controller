package ernest.airc

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Navigation uses the real activity; these tests never connect or deploy to an external target. */
@RunWith(AndroidJUnit4::class)
class RemoteNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun dynamicSurfaceRecreatesWithoutStartingOfflineCapture() {
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-screenshot").performScrollTo().performClick()
        compose.onNodeWithTag("screen-mirror-image").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("screen-mirror-image").assertExists()
        compose.onNodeWithText("开始屏幕镜像").assertDoesNotExist()
    }

    @Test fun ascrcpyAppParametersSurviveRotationAndReturnWithoutCapture() {
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-screenshot").performScrollTo().performClick()
        compose.onNodeWithText("1920 · 8 Mbps · 60 fps").assertDoesNotExist()
        compose.onNodeWithTag("mirror-toggle-remote").performScrollTo().assertExists()
        compose.onNodeWithText("开始屏幕镜像").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("1920 · 8 Mbps · 60 fps").assertDoesNotExist()
        compose.onNodeWithTag("mirror-toggle-remote").performScrollTo().assertExists()
        compose.onNodeWithText("停止屏幕镜像").assertDoesNotExist()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("feature-menu").assertIsDisplayed()
    }
    @Test fun gamepadRotationKeepsUnifiedOfflineControlsNeutral() {
        compose.onNodeWithTag("open-remote-types").performClick()
        compose.onNodeWithTag("remote-type-gamepad").performScrollTo().performClick()
        compose.onNodeWithTag("virtual-button-a").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("virtual-stick-left").assertExists()
        compose.onNodeWithTag("virtual-stick-right").assertExists()
        compose.onNodeWithTag("virtual-button-a").assertIsNotEnabled()
        compose.onNodeWithContentDescription("开始手柄").assertIsNotEnabled()
        compose.onNodeWithTag("gamepad-advanced").assertDoesNotExist()
        compose.onNodeWithTag("device-status").performClick()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("gamepad-page").assertExists()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("remote-types-page").assertIsDisplayed()
    }
    @Test fun ascrcpyReplacesLegacyPngPreviewAfterRotation() {
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-screenshot").performScrollTo().performClick()
        compose.onNodeWithText("间隔 2 秒").assertDoesNotExist()
        compose.onNodeWithText("开始屏幕预览").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("1920 · 8 Mbps · 60 fps").assertDoesNotExist()
        compose.onNodeWithTag("mirror-toggle-remote").performScrollTo().assertExists()
        compose.onNodeWithText("停止屏幕预览").assertDoesNotExist()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("feature-menu").assertIsDisplayed()
    }
    @Test fun mediaControlDraftSurvivesRotationAndBackReturnsThroughMenu() {
        compose.onNodeWithTag("open-remote-types").performClick()
        compose.onNodeWithTag("remote-type-media_control").performScrollTo().performClick()
        compose.onNodeWithTag("media-seek-position").performScrollTo().performTextInput("7000")
        compose.onNodeWithText("提交播放定位").performScrollTo().assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("media-seek-position").performScrollTo().assertTextContains("7000")
        compose.onNodeWithTag("device-status").performClick()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("media-control-page").assertIsDisplayed()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("remote-types-page").assertIsDisplayed()
    }

    @Test fun connectionStatusOpensDeviceManagementAndBackReturnsHome() {
        compose.onNodeWithTag("remote-page").assertIsDisplayed()
        compose.onNodeWithText("应用包名").assertDoesNotExist()
        compose.onNodeWithText("查看应用列表").assertDoesNotExist()
        compose.onNodeWithTag("device-status").performClick()
        compose.onNodeWithTag("devices-page").assertIsDisplayed()
        compose.onNodeWithText("诊断服务连接").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("连接并部署").assertDoesNotExist()
        compose.onNodeWithTag("connection-expand").performScrollTo().performClick()
        compose.onNodeWithText("连接并部署").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("connection-expand").performScrollTo().performClick()
        compose.onNodeWithText("连接并部署").assertDoesNotExist()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("remote-page").assertIsDisplayed()
    }
    @Test fun remoteTypePickerRestoresAndKeepsStandardRemoteDraft() {
        configuredOfflineComposer(compose)
        compose.onNodeWithTag("remote-command-input").performTextInput("保留遥控草稿")
        compose.onNodeWithTag("open-remote-types").performClick()
        compose.onNodeWithTag("remote-types-page").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("remote-type-touchpad").performClick()
        compose.onNodeWithTag("touchpad-page").assertIsDisplayed()
        compose.onNodeWithTag("open-remote-types").performClick()
        compose.onNodeWithTag("remote-type-remote").performClick()
        compose.onNodeWithTag("remote-command-input").assertTextContains("保留遥控草稿")
        compose.onNodeWithTag("open-menu").performClick()
        listOf("touchpad", "keypad", "media_control", "gamepad").forEach {
            compose.onNodeWithTag("feature-$it").assertDoesNotExist()
        }
    }

    @Test fun allFeaturesOpenAndReturnToTheGrid() {
        compose.onNodeWithTag("remote-status").assertIsDisplayed()
        compose.onNodeWithTag("remote-media-controls").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("播放 / 暂停").assertIsNotEnabled()
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-media").assertDoesNotExist()
        val destinations = listOf(
            "apps" to "apps-page", "text" to "text-page",
            "model" to "model-page", "service" to "service-page", "permissions" to "permissions-page",
            "device_info" to "device-info-page", "results" to "results-page", "help" to "help-page",
            "screenshot" to "screenshot-page", "files" to "files-page", "settings" to "settings-page",
        )
        for ((feature, page) in destinations) {
            compose.onNodeWithTag("feature-$feature").performScrollTo().performClick()
            compose.onNodeWithTag(page).assertIsDisplayed()
            compose.onNodeWithTag("navigate-back").performClick()
            compose.onNodeWithTag("feature-menu").assertIsDisplayed()
        }
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("remote-page").assertIsDisplayed()
    }
    @Test fun inputPagesStayDisabledOfflineAndDeviceBackRestoresTouchpad() {
        compose.onNodeWithTag("open-remote-types").performClick()
        compose.onNodeWithTag("remote-type-touchpad").performScrollTo().performClick()
        compose.onNodeWithTag("touchpad-area").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("点击屏幕中心").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("device-status").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("touchpad-page").assertIsDisplayed()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("remote-type-keypad").performScrollTo().performClick()
        compose.onNodeWithTag("keypad-1").assertIsNotEnabled()
        compose.onNodeWithTag("keypad-channel_up").performScrollTo().assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("keypad-page").assertIsDisplayed()
    }

    @Test fun appSearchSurvivesRecreationAndOfflineRefreshIsDisabled() {
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-apps").performScrollTo().performClick()
        compose.onNodeWithTag("apps-refresh").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("apps-search").performScrollTo().performTextInput("com.example")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("apps-search").performScrollTo().assertTextContains("com.example")
        compose.onNodeWithTag("apps-refresh").performScrollTo().assertIsNotEnabled()
    }

    @Test fun offlineDeviceInfoRemainsBrowsableAndRefreshIsDisabledAfterRecreation() {
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-device_info").performScrollTo().performClick()
        compose.onNodeWithText("刷新设备信息").performScrollTo().assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("device-info-page").assertIsDisplayed()
        compose.onNodeWithText("刷新设备信息").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("feature-menu").assertIsDisplayed()
    }

    @Test fun systemBackAndDeviceManagementPreserveFeatureOriginAcrossRecreation() {
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-model").performClick()
        compose.onNodeWithTag("device-status").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("devices-page").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("model-page").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("feature-menu").assertIsDisplayed()
    }
    @Test fun screenshotPageRestoresAfterRotationAndDevicePageReturnsToIt() {
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-screenshot").performScrollTo().performClick()
        compose.onNodeWithText("获取 / 刷新截图").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("screenshot-page").assertIsDisplayed()
        compose.onNodeWithTag("device-status").performClick()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("screenshot-page").assertIsDisplayed()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("feature-menu").assertIsDisplayed()
    }

    @Test fun fileBrowserRestoresAfterRotationAndDisablesOfflineRequests() {
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-files").performScrollTo().performClick()
        compose.onNodeWithTag("files-refresh").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("files-page").assertIsDisplayed()
        compose.onNodeWithTag("device-status").performClick()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("files-page").assertIsDisplayed()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("feature-menu").assertIsDisplayed()
    }

    @Test fun textDraftSurvivesFeatureSwitchAndOfflineRemoteActionsStayDisabled() {
        compose.onNodeWithTag("remote-send-command").assertDoesNotExist()
        compose.onNodeWithTag("remote-model-required").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-text").performClick()
        compose.onNodeWithTag("text-command-input").performTextInput("打开设置")
        compose.onNodeWithTag("device-status").performClick()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithText("打开设置").assertIsDisplayed()
        compose.onNodeWithText("发送指令").assertIsNotEnabled()
    }
}
