package ernest.airc
import kotlin.test.*
class RemoteLayoutTest {
    @Test fun layoutRejectsUnsafeActionsAndMovesWithoutChangingOriginal() {
        val key=RemoteShortcut("key","ok","确认");val app=RemoteShortcut("app","test.app","播放器")
        val layout=RemoteLayout(listOf(key,app))
        assertTrue(layout.valid());assertEquals(listOf(app,key),layout.move(1,-1).shortcuts);assertEquals(key,layout.shortcuts.first())
        assertFalse(RemoteLayout(listOf(key,key)).valid())
        assertFalse(RemoteShortcut("shell","reboot","重启").valid())
        assertFalse(RemoteShortcut("app","test.app;reboot","播放器").valid())
        assertFalse(RemoteShortcut("key","power","电源").valid())
        assertFalse(RemoteLayout((0..8).map { RemoteShortcut("key",it.toString(),it.toString()) }).valid())
    }
    @Test fun archivesRejectPortableTraversalNames() {
        assertTrue(validBatchArchiveName("电影.mp4"))
        assertFalse(validBatchArchiveName("../outside"))
        assertFalse(validBatchArchiveName("..\\outside"))
        assertFalse(validBatchArchiveName("C:outside"))
    }
    @Test fun conditionInputsAreBounded() {
        assertTrue(RoutineStep("wait_playing","").valid())
        assertTrue(RoutineStep("wait_app","test.app",60).valid())
        assertFalse(RoutineStep("wait_app","test.app",61).valid())
        assertFalse(RoutineStep("wait_text"," ").valid())
        assertFalse(RoutineStep("wait_text","x".repeat(201)).valid())
    }
}
