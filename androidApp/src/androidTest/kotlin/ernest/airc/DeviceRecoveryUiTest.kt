package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.*
import org.junit.Assert.*

class DeviceRecoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun scanningRotatesRefreshIconAndStopsWithoutAProgressBar() {
        var scanning by mutableStateOf(true)
        var refreshes = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { RemoteTheme { DeviceDiscoveryHeader(scanning, "fixture scan", { refreshes++ }) } }
        fun pixels(): IntArray {
            val bitmap = compose.onNodeWithTag("adb-scan-icon", useUnmergedTree = true).captureToImage().asAndroidBitmap()
            return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        }
        compose.onNodeWithTag("refresh-adb-devices").assertIsNotEnabled()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.mainClock.advanceTimeByFrame()
        val first = pixels()
        compose.mainClock.advanceTimeBy(200)
        assertFalse("Refresh icon must rotate while scanning", first.contentEquals(pixels()))
        compose.runOnIdle { scanning = false }
        compose.mainClock.advanceTimeByFrame()
        val stopped = pixels()
        compose.mainClock.advanceTimeBy(300)
        assertArrayEquals(stopped, pixels())
        compose.onNodeWithTag("refresh-adb-devices").assertIsEnabled().performClick()
        assertEquals(1, refreshes)
    }
    @Test fun onlyPendingTargetAnimatesAndMovesWhenTargetChanges() {
        val first = RememberedDevice("old", "Living room", ConnectionMethod.TCP, "192.0.2.10", lastConnectedAt=1)
        val next = RememberedDevice("next", "Bedroom", ConnectionMethod.TCP, "192.0.2.11", lastConnectedAt=2)
        var state by mutableStateOf(RemoteUiState(reconnecting=true, selected=first.id, history=listOf(first,next), connectingDevice=next))
        compose.mainClock.autoAdvance = false
        compose.setContent { RemoteTheme { Column(Modifier.width(320.dp)) {
            deviceListEntries(state).forEach { UnifiedDeviceRow(it, state, {}, {_,_->}, {}) }
        } } }
        fun pixels(): IntArray {
            val image=compose.onNodeWithTag("device-connecting-192.0.2.11", useUnmergedTree=true).captureToImage().asAndroidBitmap()
            return IntArray(image.width*image.height).also { image.getPixels(it,0,image.width,0,0,image.width,image.height) }
        }
        compose.mainClock.advanceTimeByFrame()
        val initial=pixels()
        compose.mainClock.advanceTimeBy(250)
        assertFalse(initial.contentEquals(pixels()))
        compose.onNode(hasText("连接中") and hasAnyAncestor(hasTestTag("device-row-192.0.2.11"))).assertIsNotEnabled()
        compose.onNode(hasText("连接") and hasAnyAncestor(hasTestTag("device-row-192.0.2.10"))).assertIsEnabled()
        compose.runOnIdle { state=state.copy(connectingDevice=first) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("device-connecting-192.0.2.11", useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithTag("device-connecting-192.0.2.10", useUnmergedTree=true).assertExists()
        compose.runOnIdle { state=state.copy(reconnecting=false, connected=true, connectingDevice=null) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("device-connecting-192.0.2.10", useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText("已连接").assertIsNotEnabled()
    }
    @Test fun anotherDeviceRemainsSelectableWhileConnectionRecoveryIsBusy() {
        var state by mutableStateOf(RemoteUiState(busy = true, selected = "old", history = listOf(
            RememberedDevice("old", "Old target", ConnectionMethod.TCP, "192.0.2.10", lastConnectedAt = 1),
            RememberedDevice("new", "New target", ConnectionMethod.TCP, "192.0.2.11", lastConnectedAt = 2),
        )))
        val selected = mutableListOf<String>()
        compose.setContent { RemoteTheme { Column(Modifier.width(320.dp)) {
            deviceListEntries(state).forEach { entry -> UnifiedDeviceRow(entry, state,
                onConnect = { selected += entry.host }, onRename = { _, _ -> }, onForget = {}) }
        } } }
        val connectNew = hasText("连接") and hasAnyAncestor(hasTestTag("device-row-192.0.2.11"))
        compose.onNode(connectNew).assertIsEnabled().performClick()
        compose.runOnIdle { state = state.copy(connected = true) }
        compose.onNodeWithText("已连接").assertIsNotEnabled()
        compose.onNode(connectNew).assertIsEnabled().performClick()
        assertEquals(listOf("192.0.2.11", "192.0.2.11"), selected)
    }
}
