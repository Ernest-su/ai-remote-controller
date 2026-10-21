package ernest.airc

import kotlin.test.*

class TargetMediaFormatTest {
    @Test fun aacCanBePreviewedAndSentToTargetPlayer() {
        for (name in listOf("recording.aac","recording.AAC")) {
            assertTrue(isTargetMedia(name))
            assertEquals(FilePreviewKind.AUDIO,filePreviewKind(TargetFileEntry(name,"file")))
        }
        assertFalse(isTargetMedia("recording.aac.txt"))
        assertFalse(isTargetMedia("recording.apk"))
    }
}
