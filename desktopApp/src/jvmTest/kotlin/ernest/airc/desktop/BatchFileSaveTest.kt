package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BatchFileSaveTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun individualFilesKeepBytesAndRenameCollisionsWithoutOverwriting() = runBlocking {
        val folder = temporary.newFolder("destination")
        val one = temporary.newFile("one.cache").apply { writeText("first content") }
        val two = temporary.newFile("two.cache").apply { writeText("中文文件内容") }
        folder.resolve("电影.txt").writeText("original")
        folder.resolve("电影 (1).txt").writeText("previous copy")
        val saved = mutableListOf<String>()
        saveBatchFiles(folder.toPath(), listOf(BatchFileExport("电影.txt", one), BatchFileExport("second.txt", two)), saved::add)
        assertEquals(listOf("电影.txt", "second.txt"), saved)
        assertEquals("original", folder.resolve("电影.txt").readText())
        assertEquals("previous copy", folder.resolve("电影 (1).txt").readText())
        assertEquals("first content", folder.resolve("电影 (2).txt").readText())
        assertEquals("中文文件内容", folder.resolve("second.txt").readText())
        assertFalse(folder.resolve("下载文件.zip").exists())
        val long = "电".repeat(83) + ".txt"
        val suggestion = suggestedDownloadName(long) { it == long }
        assertTrue(suggestion.encodeToByteArray().size <= 255)
        assertTrue(suggestion.endsWith(" (1).txt"))
    }
    @Test fun partialFailureKeepsSavedFilesAndRemovesOnlyNewPartialOutput() = runBlocking {
        val folder = temporary.newFolder("partial")
        val source = temporary.newFile("source").apply { writeText("saved bytes") }
        val missing = java.io.File(temporary.root, "missing")
        val saved = mutableListOf<String>()
        try {
            saveBatchFiles(folder.toPath(), listOf(BatchFileExport("one.txt", source), BatchFileExport("two.txt", missing)), saved::add)
            fail("missing source should fail")
        } catch (_: java.io.FileNotFoundException) { }
        assertEquals(listOf("one.txt"), saved)
        assertEquals("saved bytes", folder.resolve("one.txt").readText())
        assertFalse(folder.resolve("two.txt").exists())
    }
}
