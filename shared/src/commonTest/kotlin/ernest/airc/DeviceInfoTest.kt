package ernest.airc

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException

class DeviceInfoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun partialProbePreservesUnknownFields() {
        val info = json.decodeFromString<DeviceInfo>("""{"model":"TV","sdk":34,"manufacturer":null,"futureField":true}""")
        assertEquals("TV", info.model)
        assertEquals(34, info.sdk)
        assertNull(info.manufacturer)
        assertNull(info.screen)
        assertNull(info.storage)
    }

    @Test fun nestedValuesAndLargeStorageAreDecodedWithoutTruncation() {
        val info = json.decodeFromString<DeviceInfo>("""{"abis":["arm64-v8a"],"screen":{"width":1920,"height":1080},"storage":{"path":"/data","totalBytes":8589934592,"usedBytes":4294967296,"availableBytes":4294967296}}""")
        assertEquals(DeviceScreenSize(1920, 1080), info.screen)
        assertEquals(8589934592L, info.storage?.totalBytes)
        assertEquals(listOf("arm64-v8a"), info.abis)
    }

    @Test fun legacyTextResultIsRejected() {
        assertFailsWith<SerializationException> { json.decodeFromString<DeviceInfo>("old runtime output") }
    }
}
