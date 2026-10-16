package ernest.airc

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** The network cannot replace the release trust root compiled into this APK. */
object ReleaseTrust {
    const val PUBLIC_KEY_DER = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAzyLDjfxcoVK+/dEdQo/f60KocUjrSfkhqMnpyyU+gJotgp+JqSuKznGrtn6gXkkwTWtUqwlbdHSw+j/xYdsXJkbexuwlT83isln86vIn+ZusBq9HmCyim45ETPC5K7Tlt8jZFJBHmJ4SWTASQlwWEtiB8ManRI/p3S7xwBK7kxCFWHPX0s04aniFLOgEPoYcFaRTaHxBuVbvOoNPpAlIxcnWIvw7IW8jUL4eRTKF0WYJFwoaC62sCPixu90KH3Kdxbw59LmJDZ89Z1dP0eKOEx5lrCcRfsNWgYO8VDTyC0wvpeiGxwvMHeVPv9+MKN74fgMzjC//6rL4BA4cD/o0B7btIBHLYnJHLEkvSBHm/MT32TEl374QjrFRcqGhKEjlE9Jt4jaBx4uMVVllGl/YUaZ5Lqx+EoeAmzmHauS1mFSnzAYbTbwFcx7H1ZdnQACKUzQiSINt9piMUbAXawytVoUQPVx6rW8BtO/UDx3AUnVt9s6g8llYdeKcDoc4axsPAgMBAAE="
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
