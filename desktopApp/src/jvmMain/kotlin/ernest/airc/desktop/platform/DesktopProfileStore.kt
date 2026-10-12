package ernest.airc.desktop

import ernest.airc.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.PosixFilePermissions
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** JVM local AES key and ciphertext use owner-only files; Android keeps its Keystore adapter. */
class DesktopProfileStore(private val directory: Path) : ModelProfileStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val ciphertext = directory.resolve("models.enc")
    private val keyFile = directory.resolve("models.key")
    private fun prepare() {
        check(!Files.isSymbolicLink(directory)) { "配置目录不能是符号链接" }
        Files.createDirectories(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
    }
    private fun key(): SecretKeySpec {
        prepare()
        check(!Files.isSymbolicLink(keyFile)) { "密钥文件不能是符号链接" }
        if (!Files.exists(keyFile, NOFOLLOW_LINKS)) {
            val bytes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().encoded
            Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.write(keyFile, bytes)
        }
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"))
        return SecretKeySpec(Files.readAllBytes(keyFile), "AES")
    }
    @Synchronized override fun read(): ModelProfileSnapshot {
        if (!Files.exists(ciphertext, NOFOLLOW_LINKS)) return ModelProfileSnapshot()
        check(!Files.isSymbolicLink(ciphertext)) { "配置文件不能是符号链接" }
        val bytes = Files.readAllBytes(ciphertext)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD("airc-desktop-models-v1".toByteArray())
        return json.decodeFromString(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
    }
    @Synchronized override fun write(snapshot: ModelProfileSnapshot) {
        prepare()
        check(!Files.isSymbolicLink(ciphertext)) { "配置文件不能是符号链接" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("airc-desktop-models-v1".toByteArray())
        val bytes = cipher.iv + cipher.doFinal(json.encodeToString(snapshot).toByteArray())
        val temporary = Files.createTempFile(directory, "models-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, ciphertext, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
