package dev.ide.vcs.impl

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * At-rest encryption for the tokens the account store holds. The key is a random 32-byte value generated on
 * first use and kept beside the store with owner-only permissions, so a token never sits in a readable file
 * and never travels in a plain-text export of the app's data directory.
 *
 * This protects the file, not the process: anything running as the app can read the key. On Android the
 * store lives in app-private storage, which is the boundary that actually separates apps.
 */
internal class Secrets(private val keyFile: Path) {

    private val key: SecretKeySpec by lazy { SecretKeySpec(loadOrCreateKey(), "AES") }

    fun encrypt(plain: String): String {
        val iv = ByteArray(IV_BYTES).also { RANDOM.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return ENCODER.encodeToString(iv) + ":" + ENCODER.encodeToString(encrypted)
    }

    /** Decrypt a value produced by [encrypt], or null when the text is not one (a truncated or stale file). */
    fun decrypt(encoded: String): String? {
        val parts = encoded.split(':', limit = 2)
        if (parts.size != 2) return null
        return runCatching {
            val iv = DECODER.decode(parts[0])
            val payload = DECODER.decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(payload).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun loadOrCreateKey(): ByteArray {
        if (Files.exists(keyFile)) {
            val existing = runCatching { DECODER.decode(Files.readAllBytes(keyFile)) }.getOrNull()
            if (existing != null && existing.size == KEY_BYTES) return existing
        }
        val fresh = ByteArray(KEY_BYTES).also { RANDOM.nextBytes(it) }
        Files.createDirectories(keyFile.parent)
        Files.write(keyFile, ENCODER.encode(fresh))
        restrictToOwner(keyFile)
        return fresh
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        val RANDOM = SecureRandom()
        val ENCODER: Base64.Encoder = Base64.getEncoder()
        val DECODER: Base64.Decoder = Base64.getDecoder()
    }
}

/**
 * Narrow a file to owner-only access where the filesystem supports it. Android's app-private storage is
 * already per-app, and a filesystem without POSIX permissions simply keeps its defaults.
 */
internal fun restrictToOwner(path: Path) {
    runCatching {
        Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
    }
}
