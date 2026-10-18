package ernest.airc

fun directionForSwipe(dx: Float, dy: Float): String =
    if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) { if (dx >= 0) "right" else "left" }
    else { if (dy >= 0) "down" else "up" }
