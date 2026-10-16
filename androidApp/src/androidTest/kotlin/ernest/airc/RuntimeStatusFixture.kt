package ernest.airc

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*

internal fun alignedRuntimeStatusFixture(payload: String): String {
    val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
    val release = assets.open("runtime/manifest.json").bufferedReader().use { Json.decodeFromString<RuntimeRelease>(it.readText()) }
    return JsonObject(Json.parseToJsonElement(payload).jsonObject + mapOf(
        "version" to JsonPrimitive(release.version),
        "binarySha256" to JsonPrimitive(release.assets.first().sha256),
    )).toString()
}
