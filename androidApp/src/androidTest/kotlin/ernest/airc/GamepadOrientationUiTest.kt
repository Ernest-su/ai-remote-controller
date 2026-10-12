package ernest.airc

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class GamepadOrientationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun transition(orientation:Int,policy:Int?=null,action:()->Unit) {
        val before=compose.activity
        val requiresRecreation=before.resources.configuration.orientation!=orientation
        action()
        compose.waitUntil(15000){
            val current=compose.activity
            (!requiresRecreation||current!==before) && !current.isChangingConfigurations &&
                current.lifecycle.currentState==Lifecycle.State.RESUMED &&
                current.resources.configuration.orientation==orientation &&
                (policy==null||current.requestedOrientation==policy)
        }
        compose.waitForIdle()
    }
    @Test fun gamepadEntersLandscapeSurvivesRecreationAndRestoresPolicyOnExit(){
        val original=compose.activity.requestedOrientation
        val originalOrientation=compose.activity.resources.configuration.orientation
        compose.onNodeWithTag("open-remote-types").performClick()
        transition(Configuration.ORIENTATION_LANDSCAPE,ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE){
            compose.onNodeWithTag("remote-type-gamepad").performScrollTo().performClick()
        }
        compose.onNodeWithTag("virtual-stick-left").assertIsDisplayed()
        compose.onNodeWithTag("virtual-stick-right").assertIsDisplayed()
        compose.onNodeWithTag("virtual-button-start").assertIsDisplayed()
        compose.onNodeWithTag("virtual-button-select").assertIsDisplayed()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,compose.activity.requestedOrientation)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("gamepad-page").assertIsDisplayed()
        assertEquals(Configuration.ORIENTATION_LANDSCAPE,compose.activity.resources.configuration.orientation)
        transition(originalOrientation,original){compose.onNodeWithTag("navigate-back").performClick()}
        compose.onNodeWithTag("remote-types-page").assertIsDisplayed()
    }
    @Test fun devicePageRoundTripAndSystemBackRestoreOrientationWithDraftRetained(){
        val original=compose.activity.requestedOrientation
        val originalOrientation=compose.activity.resources.configuration.orientation
        compose.onNodeWithTag("open-remote-types").performClick()
        transition(Configuration.ORIENTATION_LANDSCAPE,ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE){
            compose.onNodeWithTag("remote-type-gamepad").performScrollTo().performClick()
        }
        compose.onNodeWithTag("gamepad-advanced").performScrollTo().performClick()
        compose.onNodeWithText("300 毫秒").performScrollTo().performClick()
        transition(originalOrientation,original){compose.onNodeWithTag("device-status").performClick()}
        transition(Configuration.ORIENTATION_LANDSCAPE,ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE){compose.onNodeWithTag("navigate-back").performClick()}
        compose.onNodeWithText("300 毫秒").performScrollTo().assertIsSelected()
        transition(originalOrientation,original){androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)}
        compose.onNodeWithTag("remote-types-page").assertIsDisplayed()
        transition(Configuration.ORIENTATION_LANDSCAPE,ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE){
            compose.onNodeWithTag("remote-type-gamepad").performScrollTo().performClick()
        }
        compose.onNodeWithText("300 毫秒").performScrollTo().assertIsSelected()
        transition(originalOrientation,original){androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)}
    }

}
