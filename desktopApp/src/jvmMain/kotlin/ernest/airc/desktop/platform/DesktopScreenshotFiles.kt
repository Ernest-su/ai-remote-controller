package ernest.airc.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object DesktopScreenshotFiles {
    fun save(destination: Path, png: ByteArray) {
        // Never overwrite an existing user file. A failed new write removes its partial output.
        val stream = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try { stream.use { it.write(png) } }
        catch (error: Exception) { Files.deleteIfExists(destination); throw error }
    }
}
