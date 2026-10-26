package ernest.airc.desktop

import ernest.airc.*
import org.junit.Test
import org.junit.Assert.*

class FileTextHighlightTest {
    @Test fun longStringsAndUnterminatedTokensDoNotOverflowTheStack() {
        val bodies = listOf("a".repeat(32_000), "\\\"".repeat(16_000))
        val inputs = bodies.flatMap { listOf("\"$it\"", "\"$it", "'$it'") } +
            listOf("/*" + "a".repeat(32_000), "//" + "a".repeat(32_000))
        for (source in inputs) {
            val result = highlightedFileText(source, LightRemotePalette.Action, LightRemotePalette.Secondary)
            assertEquals(source, result.text)
            assertEquals(1, result.spanStyles.size)
            assertEquals(source.length, result.spanStyles.single().end)
        }
    }
    @Test fun highlightsTokensWithoutChangingTextAndBoundsLongInputs() {
        val source = "const x = \"中文\"; // comment\nreturn 42"
        for (palette in listOf(LightRemotePalette, DarkRemotePalette)) {
            val text = highlightedFileText(source, palette.Action, palette.Secondary)
            assertEquals(source, text.text)
            assertTrue(text.spanStyles.isNotEmpty())
            assertTrue(text.spanStyles.all { it.start >= 0 && it.end <= source.length })
            val long = highlightedFileText("x".repeat(TEXT_HIGHLIGHT_CHARS + 1), palette.Action, palette.Secondary)
            assertTrue(long.spanStyles.isEmpty())
        }
    }
}
