package ernest.airc.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import ernest.airc.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppDetailsUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = TargetAppInfo("fixture.app", 10, 10001, 1, codePath = "/data/app/fixture", lastUpdateTime = "fixture", system = false)
    private val snapshot = AppControlSnapshot("fixture.app", 10, true, 0, false, AppControlCapabilities(true, true, true), listOf(
        AppPermission("android.permission.CAMERA", "相机", false, true, true),
        AppPermission("android.permission.SYSTEM_ALERT_WINDOW", "悬浮窗", false, false, false, "特殊访问仅供查看")))
    private val tools = listOf("apps.control_info", "apps.prepare_control", "apps.clear_cache", "apps.uninstall")
    @Test fun permissionsAreObservedUntilConfirmationAndSpecialAccessHasNoSwitch() {
        var action: Pair<String, String?>? = null
        var state by mutableStateOf(AppControlState("fixture.app", details = AppControlDetails(app, snapshot)))
        var confirmed = false
        compose.setContent { RemoteTheme { AppDetailsPanel("fixture.app", state, true, tools, false, {}, { a, p -> action = a to p }, { confirmed = it }, {}, {}, {}) } }
        compose.onNodeWithTag("app-permission-android.permission.CAMERA").performScrollTo().assertIsOff().performClick()
        assertEquals("grant" to "android.permission.CAMERA", action)
        compose.onNodeWithTag("app-permission-android.permission.CAMERA").assertIsOff()
        compose.onNodeWithTag("app-permission-android.permission.SYSTEM_ALERT_WINDOW").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(busy = true, proposal = AppControlProposal("id", "app_control", app, "clear_data", approvalRequired = true, dataLoss = true, before = snapshot)) }
        compose.onNodeWithText(appControlImpact("clear_data")).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertFalse(confirmed)
        compose.onNodeWithTag("app-control-confirm").performClick()
        assertTrue(confirmed)
    }
    @Test fun offlineDetailsDisableMutationsButRemainReadable() {
        compose.setContent { RemoteTheme { AppDetailsPanel("fixture.app", AppControlState("fixture.app", details = AppControlDetails(app, snapshot)), false, tools, false, {}, { _, _ -> error("offline mutation") }, {}, {}, {}, {}) } }
        compose.onNodeWithText("强行停止").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("清除全部数据").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("app-permission-android.permission.CAMERA").performScrollTo().assertIsNotEnabled()
    }
    @Test fun switchablePermissionsComeFirstWithStableOrderIncludingOffline() {
        val permissions = listOf(
            AppPermission("normal.first", "普通权限一", true, false, false, "仅供查看"),
            AppPermission("runtime.first", "可修改权限一", false, true, true),
            AppPermission("fixed.second", "受限权限二", false, true, false, "系统策略限制"),
            AppPermission("runtime.second", "可修改权限二", true, true, true))
        var connected by mutableStateOf(true)
        compose.setContent { RemoteTheme {
            AppDetailsPanel("fixture.app", AppControlState("fixture.app", details = AppControlDetails(app, snapshot.copy(permissions = permissions))),
                connected, tools, false, {}, { _, _ -> }, {}, {}, {}, {})
        } }
        fun assertOrder() {
            val positions = listOf("可修改权限一", "可修改权限二", "普通权限一", "受限权限二").map {
                compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
            }
            assertTrue("Permission rows must keep stable order within both groups: $positions", positions.zipWithNext().all { (a, b) -> a < b })
        }
        assertOrder()
        compose.runOnIdle { connected = false }
        assertOrder()
    }
    @Test fun detailsAndDeviceReturnPathsKeepApplicationListAsParent() {
        assertEquals(RemotePage.APPS, RemotePage.APP_DETAILS.backDestination(RemotePage.REMOTE))
        assertEquals(RemotePage.APP_DETAILS, RemotePage.DEVICES.backDestination(RemotePage.APP_DETAILS))
        val nav = DesktopNavigation()
        nav.navigate(RemotePage.APP_DETAILS); nav.navigate(RemotePage.DEVICES); nav.back()
        assertEquals(RemotePage.APP_DETAILS, nav.page)
        nav.back(); assertEquals(RemotePage.APPS, nav.page)
    }
}
