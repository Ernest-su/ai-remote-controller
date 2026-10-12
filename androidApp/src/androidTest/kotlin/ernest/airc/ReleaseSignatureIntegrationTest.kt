package ernest.airc

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseSignatureIntegrationTest {
    @Test fun platformVerifiesThePreparedPublicationWithPinnedTrust() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assumeTrue("build with -PaircReleaseFixture to include the signed publication", assets.list("")!!.contains("manifest.json"))
        val manifest = assets.open("manifest.json").use { it.readBytes() }
        val signature = assets.open("manifest.json.sig").use { it.readBytes() }
        ReleaseTrust.verifier().verify(manifest, signature)
        val tampered = manifest.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertThrows(IllegalStateException::class.java) { ReleaseTrust.verifier().verify(tampered, signature) }
    }
}
