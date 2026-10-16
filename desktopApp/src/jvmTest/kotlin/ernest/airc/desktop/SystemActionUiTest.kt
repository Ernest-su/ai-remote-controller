package ernest.airc.desktop
import ernest.airc.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.*
class SystemActionUiTest {
    @get:Rule val compose=createComposeRule()
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
