package ernest.airc.desktop

import ernest.airc.validatedDeviceName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class DesktopDeviceNames(private val directory: Path) {
    private val file get() = directory.resolve("device-names.json")
    @Synchronized fun read(): Map<String, String> = if (Files.exists(file))
        Json.decodeFromString<Map<String, String>>(Files.readString(file)) else emptyMap()

    @Synchronized fun rename(id: String, value: String) {
        require(id.isNotBlank())
        val names = read() + (id to validatedDeviceName(value))
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "device-names-", ".tmp")
        try {
            Files.writeString(temporary, Json.encodeToString(names))
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
