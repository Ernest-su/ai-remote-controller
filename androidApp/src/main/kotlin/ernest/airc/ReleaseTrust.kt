package ernest.airc

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** The network cannot replace the release trust root compiled into this APK. */
object ReleaseTrust {
    const val PUBLIC_KEY_DER = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEApX990gb0cTO9EtnJ2zqL9B7LuC77gRZI7tmnYePImufotYa5rbLdU6/B2srd/qkghcHlNoz4kltkoDn+ZkbdSY43zjL6YnNs4p66hkI9BULQ8BsNKGglD5HSGaFHJ0Tx4m4V7VlscvGHSqge2B8txPTUX1WJHMGOu6SjurnP1pdfcbGybMs3b+AmAVmqVP2mB5ywhdGj0BtBXjvWkC7bmvrfb6s/rAtKlKh1fgp9Oim+R1vGh6xmDbugNmbK5yyK5+q2mlxVu/MRVo9l1kURGWI9yR7R6dv12l5PsT9OTgTGDxBhzNqaOkinPMWqw7T3QBKxgWRiqU8CjltwTS+wQHvPj4gXVjjbKItOVgF0tr4+CPOwMMCzXzdOJ1qsrdFRHfK6AqdE3UWsLWNdLn8M5yyCaae2Cd7fhpqtweYUC2Jnalpl/cBp41Lafwlr+BnQmL6ljboIIX1ImmcKNUdQMCW5u9fIUhQxtJYIxcSKqKsSgstpVKcw5ADmmuCjDC6LAgMBAAE="
    fun verifier() = ReleaseSignatureVerifier(KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_DER))))
}

class ReleaseSignatureVerifier(private val key: PublicKey) {
    init { require(key.algorithm == "RSA") }
    fun verify(manifest: ByteArray, signature: ByteArray) {
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        verifier.update(manifest)
        val valid = try { verifier.verify(signature) } catch (_: java.security.SignatureException) { false }
        check(valid) { "发布清单签名验证失败" }
    }
}
