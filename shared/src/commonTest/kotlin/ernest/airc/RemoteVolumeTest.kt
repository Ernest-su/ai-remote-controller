package ernest.airc
import kotlin.test.*
class RemoteVolumeTest {
    private fun snapshot(index: Int, min: Int = 0, max: Int = 100, fixed: Boolean = false) =
        """{"volumeIndex":$index,"minVolumeIndex":$min,"maxVolumeIndex":$max,"fixed":$fixed}"""
    @Test fun readsActualVolumeAndAccountsForMinimum() {
        assertEquals(0.5f, parseRemoteVolume(snapshot(9, 3, 15))!!.fraction)
        assertEquals(0f, parseRemoteVolume(snapshot(0))!!.fraction)
        assertEquals(1f, parseRemoteVolume(snapshot(100))!!.fraction)
    }
    @Test fun unavailableFixedAndInvalidReadingsAreRejected() {
        listOf("Permission denied", snapshot(1, 0, 0), snapshot(101), snapshot(0, 1),
            snapshot(1, 3, 2), snapshot(57, fixed = true), "{}").forEach { assertNull(parseRemoteVolume(it), it) }
    }
}
