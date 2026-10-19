package ernest.airc

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalFocusManager

/** Observe before children so a held button or editor can also become a panel drag. */
@Composable
internal fun Modifier.mirrorRemoteDrag(
    onStart: () -> Unit,
    immediate: Boolean = false,
    onEnd: () -> Unit = {},
    canStart: (Offset) -> Boolean = { true },
    onDrag: (Offset) -> Unit,
): Modifier {
    val start = rememberUpdatedState(onStart)
    val drag = rememberUpdatedState(onDrag)
    val accepts = rememberUpdatedState(canStart)
    val end = rememberUpdatedState(onEnd)
    val focus = LocalFocusManager.current
    return pointerInput(focus, immediate) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!accepts.value(down.position)) return@awaitEachGesture
            var distance = Offset.Zero
            var dragging = false
            var moving = true
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val tracked = event.changes.firstOrNull { it.id == down.id }
                    val pressed = event.changes.count { it.pressed }
                    if (dragging) {
                        // Continue consuming through multi-touch and release, even after moving stops.
                        // Read the delta before consumption; positionChange() is zero after consume().
                        val movement = tracked?.positionChange() ?: Offset.Zero
                        event.changes.forEach { it.consume() }
                        if (pressed != 1 || tracked?.pressed != true) moving = false
                        if (pressed == 0) break
                        if (moving) drag.value(movement)
                    } else {
                        val change = tracked ?: break
                        if (!change.pressed || pressed != 1) break
                        // Accumulate physical deltas so keyboard/viewport relocation cannot start a drag.
                        distance += change.positionChange()
                        val beyondSlop = distance.getDistance() > viewConfiguration.touchSlop
                        if (!immediate && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                            // Quick swipes remain editor selection/scroll gestures; a stationary hold stays a key hold.
                            if (beyondSlop) break
                        } else if (beyondSlop) {
                            dragging = true
                            event.changes.forEach { it.consume() }
                            start.value()
                            focus.clearFocus()
                            drag.value(distance)
                        }
                    }
                }
            } finally {
                if (dragging) end.value()
            }
        }
    }
}
