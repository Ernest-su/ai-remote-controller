package ernest.airc
import ernest.airc.*
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
class SystemObservationUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun unknownObservationsAndOfflineRefreshDoNotClaimCompletion(){
        var queried=false
        compose.setContent{RemoteTheme{PageContent{SystemObservationPanel(SystemObservationState(observation=TargetSystemState(2000,true,false,"android_framework")),false){queried=true}}}}
        compose.onNodeWithText("系统状态不可用").assertIsDisplayed()
        compose.onAllNodesWithText("未知").assertCountEquals(5)
        compose.onNodeWithText("刷新电源与锁屏状态").assertIsNotEnabled()
        assertFalse(queried)
    }
    @Test fun snapshotShowsControllerReceiptTime(){
        compose.setContent{RemoteTheme{PageContent{SystemObservationPanel(SystemObservationState(observation=TargetSystemState(0,true,true,"android_framework",userId=0,elapsedRealtimeMs=12,interactive=true),receivedAt="2026-10-14 12:00:00 +0800"),true){}}}}
        compose.onNodeWithText("收到时间（控制端）").assertIsDisplayed()
        compose.onNodeWithText("2026-10-14 12:00:00 +0800").assertIsDisplayed()
    }

}
