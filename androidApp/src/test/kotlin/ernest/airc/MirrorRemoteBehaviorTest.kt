package ernest.airc

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorRemoteBehaviorTest {
    @Test fun snappingUsesNearbyEdgesAndPreservesFreePlacement() {
        val limit = Offset(500f, 300f)
        assertEquals(Offset(0f, 300f), snapMirrorRemoteOffset(Offset(12f, 288f), limit, 24f))
        assertEquals(Offset(500f, 0f), snapMirrorRemoteOffset(Offset(490f, 20f), limit, 24f))
        assertEquals(Offset(200f, 100f), snapMirrorRemoteOffset(Offset(200f, 100f), limit, 24f))
        assertEquals(Offset(0f, 300f), snapMirrorRemoteOffset(Offset(-100f, 900f), limit, 24f))
    }
    @Test fun narrowAndFullHeightViewportsChooseClosestValidEdge() {
        assertEquals(Offset(20f, 0f), snapMirrorRemoteOffset(Offset(18f, 40f), Offset(20f, 0f), 24f))
        assertEquals(Offset.Zero, snapMirrorRemoteOffset(Offset(2f, 8f), Offset.Zero, 24f))
    }
}
