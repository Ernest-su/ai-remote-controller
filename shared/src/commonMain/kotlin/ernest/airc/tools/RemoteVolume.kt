package ernest.airc

data class RemoteVolume(val index: Int, val min: Int, val max: Int) {
    val fraction: Float get() = (index - min).toFloat() / (max - min)
}

private val volumeJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

@kotlinx.serialization.Serializable
private data class VolumeSnapshot(val volumeIndex: Int, val minVolumeIndex: Int, val maxVolumeIndex: Int, val fixed: Boolean)

fun parseRemoteVolume(output: String): RemoteVolume? = runCatching {
    val snapshot = volumeJson.decodeFromString<VolumeSnapshot>(output)
    RemoteVolume(snapshot.volumeIndex, snapshot.minVolumeIndex, snapshot.maxVolumeIndex)
        .takeIf { !snapshot.fixed && it.max > it.min && it.index in it.min..it.max }
}.getOrNull()
