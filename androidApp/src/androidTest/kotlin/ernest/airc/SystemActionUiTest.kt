package ernest.airc
import ernest.airc.*
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
class SystemActionUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun confirmationNamesTargetAndCancellationIsExplicit(){
        var decision:Boolean?=null
        val p=SystemActionProposal("12345678-1234-1234-1234-123456789abc","system_action","shutdown",0,true,true,120)
        compose.setContent{RemoteTheme{SystemActionPanel(SystemActionState(busy=true,proposal=p),true,"Fixture TV",{},{decision=it})}}
        compose.onNodeWithText("目标：Fixture TV").assertIsDisplayed()
        compose.onNodeWithText("准备关机").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick();assertEquals(false,decision)
    }
    @Test fun unavailableRuntimeDisablesAllSystemActions(){
        var invoked=false
        compose.setContent{RemoteTheme{SystemActionPanel(SystemActionState(),false,"Shell TV",{invoked=true},{})}}
        for(label in listOf("准备锁屏","准备重启","准备关机"))compose.onNodeWithText(label).assertIsNotEnabled()
        compose.onNodeWithText("查看模型待确认的系统操作").assertDoesNotExist()
        assertFalse(invoked)
    }

}
