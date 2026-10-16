package ernest.airc.desktop

import ernest.airc.RuntimeRelease
import kotlinx.serialization.json.*
import java.nio.file.Path

internal fun alignedRuntimeStatusFixture(payload: String): String {
    val project = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { it.resolve("androidApp/src/main/assets/runtime/manifest.json").toFile().isFile }
    val release = Json.decodeFromString<RuntimeRelease>(project.resolve("androidApp/src/main/assets/runtime/manifest.json").toFile().readText())
    return JsonObject(Json.parseToJsonElement(payload).jsonObject + mapOf(
        "version" to JsonPrimitive(release.version),
        "binarySha256" to JsonPrimitive(release.assets.first().sha256),
    )).toString()
}
