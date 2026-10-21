package ernest.airc.desktop

import ernest.airc.BatchFileExport
import ernest.airc.suggestedDownloadName
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.FileAlreadyExistsException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun saveBatchFiles(directory: Path, files: List<BatchFileExport>, saved: (String) -> Unit) {
    require(Files.isDirectory(directory)) { "请选择有效的保存文件夹" }
    for (entry in files) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val name = suggestedDownloadName(entry.name) { Files.exists(directory.resolve(it), NOFOLLOW_LINKS) }
            val destination = directory.resolve(name)
            val output = try { Files.newOutputStream(destination, CREATE_NEW, WRITE) } catch (_: FileAlreadyExistsException) { continue }
            try {
                output.use { out -> entry.file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; out.write(buffer, 0, count) }
                } }
                saved(entry.name)
                break
            } catch (error: Exception) {
                Files.deleteIfExists(destination)
                throw error
            }
        }
    }
}
