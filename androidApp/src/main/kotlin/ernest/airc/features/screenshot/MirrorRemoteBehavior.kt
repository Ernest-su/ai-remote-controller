package ernest.airc

import androidx.compose.ui.geometry.Offset

/** Snap only within reach of an edge, preserving deliberate free placement. */
internal fun snapMirrorRemoteOffset(offset: Offset, limit: Offset, distance: Float): Offset {
    fun snap(value: Float, max: Float): Float {
        val bounded = value.coerceIn(0f, max.coerceAtLeast(0f))
        return when {
            bounded <= max / 2 && bounded <= distance -> 0f
            max - bounded <= distance -> max.coerceAtLeast(0f)
            else -> bounded
        }
    }
    return Offset(snap(offset.x, limit.x), snap(offset.y, limit.y))
}
