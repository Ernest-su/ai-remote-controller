package ernest.airc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Entire snapshot, including API keys and model caches, is encrypted in app-private storage. */
internal class EncryptedModelProfileStore(context: Context) : ModelProfileStore {
    private val preferences = context.getSharedPreferences("model-profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val alias = "airc-model-profiles-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized override fun read(): ModelProfileSnapshot {
        val stored = preferences.getString("encrypted-v1", null) ?: return ModelProfileSnapshot()
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > 12) { "模型配置存储格式无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        val plaintext = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        return json.decodeFromString<ModelProfileSnapshot>(plaintext.toString(Charsets.UTF_8))
    }

    @Synchronized override fun write(snapshot: ModelProfileSnapshot) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        check(preferences.edit().putString("encrypted-v1", stored).commit()) { "无法保存模型配置" }
    }
}
