package ernest.airc

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class MirrorFloatingDragUiTest {
    private val holdTime: Long get() = android.view.ViewConfiguration.getLongPressTimeout().toLong() + 150
    val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)

    @Test fun iconHeaderAndAnywhereDraggingKeepLayoutCloseAndViewportSafe() {
        lateinit var model: RemoteViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java] }
        var closes = 0
        compose.setContent { RemoteTheme {
            Box(Modifier.fillMaxSize()) {
                MirrorFloatingRemote(RemoteUiState(modelConfigured = true), model, { closes++ }, {}, true)
            }
        } }
        val panel = compose.onNodeWithTag("mirror-floating-remote")
        val layout = compose.onNodeWithTag("mirror-remote-layout")
        val close = compose.onNodeWithTag("mirror-close-remote")
        val handle = compose.onNodeWithContentDescription("拖动悬浮遥控").assertHeightIsEqualTo(48.dp)
        // Natural continuous movement starts immediately, without a synthetic stationary long press.
        fun dragHandle(duration: Long) {
            val before = panel.fetchSemanticsNode().boundsInRoot
            handle.performTouchInput { swipe(center, center + Offset(60f, 40f), durationMillis = duration) }
            val after = panel.fetchSemanticsNode().boundsInRoot
            assertEquals("Every horizontal movement must be applied once", before.left + 60f, after.left, 1f)
            assertEquals("Every vertical movement must be applied once", before.top + 40f, after.top, 1f)
        }
        dragHandle(200)
        dragHandle(1000) // Also cover slow movement across the parent's long-press threshold.
        val beforeHeldHandle = panel.fetchSemanticsNode().boundsInRoot
        handle.performTouchInput { down(center); advanceEventTime(holdTime); moveBy(Offset(36f, 24f)); up() }
        assertEquals(beforeHeldHandle.left + 36f, panel.fetchSemanticsNode().boundsInRoot.left, 1f)
        compose.onNodeWithText("完整").assertDoesNotExist()
        compose.onNodeWithText("紧凑").assertDoesNotExist()
        layout.assertContentDescriptionEquals("切换为完整遥控").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        assertEquals(layout.fetchSemanticsNode().boundsInRoot.right, close.fetchSemanticsNode().boundsInRoot.left, 1f)
        panel.assertHeightIsEqualTo(RemoteDimensions.CompactRemoteHeight + RemoteDimensions.MirrorRemoteHeaderHeight)

        fun movedFrom(node: SemanticsNodeInteraction, point: Offset? = null) {
            val before = panel.fetchSemanticsNode().boundsInRoot
            node.performTouchInput {
                down(point ?: center)
                advanceEventTime(holdTime)
                repeat(6) { moveBy(Offset(6f, 4f), delayMillis = 16) }
                up()
            }
            val after = panel.fetchSemanticsNode().boundsInRoot
            assertTrue("Dragging must move the panel", after.left > before.left + 20f)
            assertTrue(after.top > before.top + 10f)
        }
        movedFrom(panel, Offset(10f, 120f))
        movedFrom(compose.onNodeWithContentDescription("向上")) // Disabled controls remain draggable offline.
        movedFrom(layout)
        layout.assertContentDescriptionEquals("切换为完整遥控")
        movedFrom(close)
        compose.runOnIdle { assertEquals(0, closes) }
        panel.performTouchInput {
            down(Offset(10f, 120f)); advanceEventTime(holdTime)
            moveBy(Offset(10000f, 10000f)); up()
        }
        fun inside() {
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val bounds = panel.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= root.left - 1f && bounds.top >= root.top - 1f)
            assertTrue(bounds.right <= root.right + 1f && bounds.bottom <= root.bottom + 1f)
        }
        inside()
        layout.performClick().assertContentDescriptionEquals("切换为紧凑遥控")
        compose.onNodeWithTag("mirror-scaled-remote").assertExists()
        inside()
        val beforeFullDrag = panel.fetchSemanticsNode().boundsInRoot
        handle.performTouchInput { swipe(center, center - Offset(60f, 40f), durationMillis = 300) }
        val afterFullDrag = panel.fetchSemanticsNode().boundsInRoot
        assertEquals(beforeFullDrag.left - 60f, afterFullDrag.left, 1f)
        // Full layout can fill the viewport vertically; dragging still respects the top boundary.
        val rootTop = compose.onRoot().fetchSemanticsNode().boundsInRoot.top
        assertEquals((beforeFullDrag.top - 40f).coerceAtLeast(rootTop), afterFullDrag.top, 1f)
        inside()
        layout.performClick().assertContentDescriptionEquals("切换为完整遥控")
        close.performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun heldKeyAndEditorBecomeDragsWithoutLeakingNewPressesOrClicks() {
        val events = mutableListOf<String>()
        val sender = object : RemoteKeySender {
            override fun down(key: String): String { events += "down"; return "held" }
            override fun up(pressId: String, cancelled: Boolean) { events += if (cancelled) "cancel" else "up" }
            override fun cancelAll() { events += "cancel-all" }
        }
        var offset by mutableStateOf(Offset.Zero)
        var starts = 0
        var clicks = 0
        var draft by mutableStateOf("编辑草稿")
        compose.setContent { RemoteTheme { CompositionLocalProvider(LocalRemoteKeySender provides sender) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .width(280.dp).testTag("drag-fixture")
                    .mirrorRemoteDrag({ starts++; sender.cancelAll() }) { offset += it }) {
                    Row {
                        Box(Modifier.size(80.dp).testTag("drag-key").remoteKeyInput("ok", true) {}) { Text("OK") }
                        Box(Modifier.size(80.dp).clickable { clicks++ }) { Text("Action") }
                    }
                    OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().testTag("drag-editor"))
                }
            }
        } } }
        val key = compose.onNodeWithTag("drag-key")
        key.performTouchInput { down(center); advanceEventTime(holdTime + 100); up() }
        compose.runOnIdle {
            assertEquals(listOf("down", "up"), events)
            assertEquals(0, starts); assertEquals(Offset.Zero, offset)
            events.clear()
        }
        key.performTouchInput {
            down(center); advanceEventTime(holdTime); moveBy(Offset(40f, 30f)); up()
        }
        compose.runOnIdle {
            assertEquals(listOf("down", "cancel-all", "cancel"), events)
            assertEquals(1, starts)
            assertEquals(Offset(40f, 30f), offset)
            events.clear()
        }
        // A quick swipe does not turn a normal scrolling gesture into a panel drag.
        val beforeSwipe = compose.runOnIdle { offset }
        key.performTouchInput { down(center); moveBy(Offset(100f, 0f), 30); up() }
        compose.runOnIdle { assertEquals(beforeSwipe, offset); assertEquals(1, starts); events.clear() }
        val panel = compose.onNodeWithTag("drag-fixture")
        panel.performTouchInput {
            down(0, Offset(40f, 40f)); advanceEventTime(holdTime)
            moveTo(0, Offset(80f, 70f))
            down(1, Offset(165f, 75f))
            moveTo(0, Offset(100f, 90f))
            up(0)
            moveTo(1, Offset(168f, 75f))
            // A third pointer must still be suppressed after the original drag finger lifts.
            down(2, Offset(85f, 75f)); up(2); up(1)
        }
        compose.runOnIdle {
            assertEquals("Only the original key may start", 1, events.count { it == "down" })
            assertTrue(events.contains("cancel"))
            assertEquals(2, starts)
            assertEquals("A consumed drag must not become an action click", 0, clicks)
        }
        val beforeEditor = compose.runOnIdle { offset }
        val editor = compose.onNodeWithTag("drag-editor")
        editor.performTouchInput { down(center) }
        Thread.sleep(holdTime) // Exercise the real editor long-press/selection timer too.
        editor.performTouchInput { advanceEventTime(holdTime); moveBy(Offset(30f, 20f)); up() }
        compose.runOnIdle {
            assertEquals(3, starts)
            assertTrue(offset.x > beforeEditor.x)
            assertEquals("编辑草稿", draft)
        }
    }
}
