package ernest.airc

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope

internal data class PadGesture(val start: Offset, val end: Offset, val durationMs: Long, val dragged: Boolean)

internal suspend fun PointerInputScope.readPadGestures(send: (PadGesture) -> Unit) {
    fun normalized(point: Offset) = Offset(
        (point.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
        (point.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
    )
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        var last = down
        var dragged = false
        var cancelled = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.id != down.id && it.pressed }) cancelled = true
            val change = event.changes.find { it.id == down.id }
            if (change == null || change.isConsumed) cancelled = true
            if (change != null) {
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                last = change
                change.consume()
            }
        } while (event.changes.any { it.pressed })
        if (!cancelled) send(PadGesture(normalized(down.position), normalized(last.position),
            (last.uptimeMillis - down.uptimeMillis).coerceAtLeast(0), dragged))
    }
}
