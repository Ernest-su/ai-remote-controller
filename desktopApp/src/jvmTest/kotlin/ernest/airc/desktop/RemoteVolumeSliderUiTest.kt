package ernest.airc.desktop

import ernest.airc.*
import kotlin.math.roundToInt
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteVolumeSliderUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun touchDragKeepsSubmittedPositionUntilTargetReplies() {
        var volume by mutableStateOf<RemoteVolume?>(RemoteVolume(3, 0, 12))
        var setting by mutableStateOf(false)
        var requested: Float? = null
        compose.setContent { RemoteTheme {
            RemoteVolumeSlider(volume, true, { requested = it; setting = true }, Modifier.width(220.dp), setting)
        } }
        val slider = compose.onNodeWithTag("remote-volume-slider")
        val label = compose.onNodeWithTag("remote-volume-value")
        label.assertTextEquals("25%")
        slider.performTouchInput {
            down(Offset(width * 0.25f, centerY))
            moveTo(Offset(width * 0.8f, centerY), delayMillis = 600)
        }
        val dragging = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue(dragging > 0.5f)
        label.assertTextEquals("${(dragging * 100).roundToInt()}%")
        compose.runOnIdle { assertNull(requested) }
        slider.performTouchInput { up() }
        compose.runOnIdle { assertNotNull(requested) }
        val submitted = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals(requested!!, submitted, 0.001f)
        label.assertTextEquals("${(submitted * 100).roundToInt()}%")
        compose.runOnIdle { volume = RemoteVolume(11, 0, 12); setting = false }
        assertEquals(11f / 12f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        label.assertTextEquals("92%")
    }
    @Test fun followsReportedVolumeAndCommitsAccessibleAdjustment() {
        var volume by mutableStateOf<RemoteVolume?>(RemoteVolume(3, 0, 12))
        var connected by mutableStateOf(true)
        var requested: Float? = null
        compose.setContent { RemoteTheme { RemoteVolumeSlider(volume, connected, { requested = it }, Modifier.width(100.dp)) } }
        val slider = compose.onNodeWithTag("remote-volume-slider")
        fun value() = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals(0.25f, value())
        compose.onNodeWithTag("remote-volume-value").assertTextEquals("25%")
        compose.runOnIdle { volume = RemoteVolume(9, 0, 12) }
        assertEquals(0.75f, value())
        compose.onNodeWithTag("remote-volume-value").assertTextEquals("75%")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.runOnIdle { assertEquals(0.5f, requested); connected = false }
        slider.assertIsNotEnabled()
        compose.onNodeWithTag("remote-volume-value").assertTextEquals("—")
        compose.runOnIdle { connected = true; volume = null }
        slider.assertIsNotEnabled().assertContentDescriptionEquals("媒体音量暂不可用")
    }
}
