package ernest.airc

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class MouseNavigationUiTest {
    val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)
    @Test fun mouseTypeRestoresAndDeviceAndSystemBackReturnToTheirSources() {
        compose.onNodeWithTag("open-remote-types").performClick()
        compose.onNodeWithTag("remote-type-mouse").performScrollTo().performClick()
        compose.onNodeWithTag("mouse-page").assertIsDisplayed()
        compose.onNodeWithTag("mouse-session").assertDoesNotExist()
        compose.onNodeWithTag("mouse-pointing").assertDoesNotExist()
        compose.onNodeWithTag("mouse-button-1").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("mouse-page").assertIsDisplayed()
        compose.onNodeWithTag("device-status").performClick()
        compose.onNodeWithTag("devices-page").assertIsDisplayed()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("mouse-page").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("remote-types-page").assertIsDisplayed()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("remote-page").assertIsDisplayed()
    }
}
