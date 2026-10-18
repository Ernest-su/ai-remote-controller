package ernest.airc
import kotlin.test.*
class DirectionalGestureTest {
    @Test fun dominantAxisSelectsOneDirection() {
        assertEquals("left", directionForSwipe(-0.8f, 0.2f))
        assertEquals("right", directionForSwipe(0.8f, -0.2f))
        assertEquals("up", directionForSwipe(0.2f, -0.8f))
        assertEquals("down", directionForSwipe(-0.2f, 0.8f))
    }
}
