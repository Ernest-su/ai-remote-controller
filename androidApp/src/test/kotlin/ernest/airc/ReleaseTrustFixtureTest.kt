package ernest.airc

import org.junit.Assume.assumeNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/** Cross-provider test using the actual OpenSSL-signed publication and APK trust root. */
class ReleaseTrustFixtureTest {
    @Test fun verifiesPreparedPublicationWithThePinnedPublicKey() {
        val path = System.getProperty("airc.releaseFixture")
        assumeNotNull(path)
        val payload = File(path!!).readBytes()
        val signature = File("$path.sig").readBytes()
        ReleaseTrust.verifier().verify(payload, signature)
        val tampered = payload.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertThrows(IllegalStateException::class.java) { ReleaseTrust.verifier().verify(tampered, signature) }
    }
}
