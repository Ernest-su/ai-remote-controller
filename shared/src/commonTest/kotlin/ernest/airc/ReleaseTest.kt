package ernest.airc

import kotlin.test.*

class ReleaseTest {
    private val asset = RuntimeAsset("arm64-v8a", "airc-arm64", "a".repeat(64), 100, "https://release.test/arm64")
    private val release = RuntimeRelease("0.2.0", 1, listOf(asset))
    @Test fun choosesTargetAbiInDevicePreferenceOrder() {
        val x86 = asset.copy(abi = "x86_64")
        assertEquals(x86, release.copy(assets = listOf(asset, x86)).selectAsset(listOf("x86_64", "arm64-v8a")))
    }
    @Test fun rejectsIncompatibleOrMalformedPublicationBeforeDownload() {
        release.validate(1, online = true)
        assertFailsWith<IllegalArgumentException> { release.copy(protocol = 2).validate(1) }
        assertFailsWith<IllegalArgumentException> { release.copy(minAppVersionCode = 2).validate(1) }
        assertFailsWith<IllegalArgumentException> { release.copy(assets = listOf(asset, asset)).validate(1) }
        assertFailsWith<IllegalArgumentException> { release.copy(assets = listOf(asset.copy(file = "../binary"))).validate(1) }
        assertFailsWith<IllegalArgumentException> { release.copy(assets = listOf(asset.copy(size = 0))).validate(1) }
        assertFailsWith<IllegalArgumentException> { release.copy(assets = listOf(asset.copy(url = "http://release.test/bin"))).validate(1, online = true) }
    }
}
