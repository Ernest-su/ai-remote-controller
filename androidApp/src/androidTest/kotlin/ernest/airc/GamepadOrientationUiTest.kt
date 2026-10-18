package ernest.airc

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class GamepadOrientationUiTest {
    val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)
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
        listOf("select", "start").forEach {
            val node = compose.onNodeWithTag("virtual-button-$it").fetchSemanticsNode()
            assertTrue("$it must be fully visible in landscape", node.boundsInRoot.height >= node.size.height - 1)
        }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,compose.activity.requestedOrientation)
        compose.onNodeWithTag("gamepad-type-selector").performClick()
        compose.onNodeWithTag("gamepad-type-NES").performClick()
        compose.onNodeWithTag("gamepad-dpad").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("gamepad-page").assertIsDisplayed()
        compose.onNodeWithTag("gamepad-dpad").assertIsDisplayed()
        compose.onNodeWithTag("virtual-stick-left").assertDoesNotExist()
        assertEquals(Configuration.ORIENTATION_LANDSCAPE,compose.activity.resources.configuration.orientation)
        transition(originalOrientation,original){compose.onNodeWithTag("navigate-back").performClick()}
        compose.onNodeWithTag("remote-types-page").assertIsDisplayed()
    }
    @Test fun devicePageRoundTripAndSystemBackRestoreOrientationWithNeutralControls(){
        val original=compose.activity.requestedOrientation
        val originalOrientation=compose.activity.resources.configuration.orientation
        compose.onNodeWithTag("open-remote-types").performClick()
        transition(Configuration.ORIENTATION_LANDSCAPE,ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE){
            compose.onNodeWithTag("remote-type-gamepad").performScrollTo().performClick()
        }
        transition(originalOrientation,original){compose.onNodeWithTag("device-status").performClick()}
        transition(Configuration.ORIENTATION_LANDSCAPE,ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE){compose.onNodeWithTag("navigate-back").performClick()}
        compose.onNodeWithTag("virtual-button-a").assertIsNotEnabled()
        transition(originalOrientation,original){androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)}
        compose.onNodeWithTag("remote-types-page").assertIsDisplayed()
        transition(Configuration.ORIENTATION_LANDSCAPE,ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE){
            compose.onNodeWithTag("remote-type-gamepad").performScrollTo().performClick()
        }
        compose.onNodeWithTag("virtual-button-a").assertIsNotEnabled()
        transition(originalOrientation,original){androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)}
    }

    @Test fun titleToggleAndLandscapeControlsStayFixedInBothThemes() {
        compose.onNodeWithTag("open-remote-types").performClick()
        transition(Configuration.ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            compose.onNodeWithTag("remote-type-gamepad").performScrollTo().performClick()
        }
        val prefs=compose.activity.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
        val previous=prefs.getString("theme",null)
        try {
            for (dark in listOf(false,true)) {
                compose.runOnUiThread { prefs.edit().putString("theme",if(dark) "DARK" else "LIGHT").commit() }
                compose.waitForIdle()
                val title=compose.onNodeWithText("游戏手柄").fetchSemanticsNode().boundsInRoot
                val toggle=compose.onNodeWithTag("gamepad-session-toggle").assertIsNotEnabled().fetchSemanticsNode().boundsInRoot
                assertEquals(title.right,toggle.left,1f)
                val tags=listOf("left","right").map { "virtual-stick-$it" } +
                    listOf("a","b","x","y","select","start").map { "virtual-button-$it" }
                val before=tags.associateWith { compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
                compose.onNodeWithTag("gamepad-page").performTouchInput { swipe(centerLeft,centerRight); swipe(bottomCenter,topCenter) }
                before.forEach { (tag,rect) ->
                    val node=compose.onNodeWithTag(tag).fetchSemanticsNode()
                    assertEquals(rect,node.boundsInRoot)
                    assertEquals(node.size.height.toFloat(),node.boundsInRoot.height,1f)
                }
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(0)
                compose.onNodeWithText("全部释放并回中").assertDoesNotExist()
                val file=java.io.File(compose.activity.filesDir,"gamepad-fixed-${if(dark) "dark" else "light"}.png")
                file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            }
        } finally {
            compose.runOnUiThread { prefs.edit().apply { if(previous==null) remove("theme") else putString("theme",previous) }.commit() }
        }
    }

}
