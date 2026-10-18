package ernest.ascrcpy.scrcpy

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ClipboardPasteMessageTest {
    @Test fun serializesUnicodeWithByteLengthAndExplicitPasteAcknowledgement() {
        val text = "中文 %s; \$(reboot) 🎬"
        val message = ByteBuffer.wrap(clipboardPasteMessage(text))
        assertEquals(9, message.get().toInt())
        assertEquals(1L, message.long)
        assertEquals(1, message.get().toInt())
        val length = message.int
        assertEquals(text.toByteArray(Charsets.UTF_8).size, length)
        val payload = ByteArray(length); message.get(payload)
        assertEquals(text, payload.toString(Charsets.UTF_8))
        assertEquals(0, message.remaining())
    }
    @Test fun rejectsOversizeUtf8AndEmptyPayload() {
        for (value in listOf("", "中".repeat(22000))) {
            try { clipboardPasteMessage(value); fail("Must reject invalid size") } catch (_: IllegalArgumentException) { }
        }
    }
}
