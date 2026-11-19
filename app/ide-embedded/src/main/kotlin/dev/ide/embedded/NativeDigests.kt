package dev.ide.embedded

import java.security.MessageDigestSpi
import java.security.Provider
import java.security.Security

/**
 * SHA-256, SHA-1 and MD5 by the app's native code, for every `MessageDigest` in this JVM.
 *
 * A build is full of digests: task fingerprints, the dex cache's keys, a jar's and an APK's signatures. The
 * JDK computes them in Java, which a compiling JVM turns into a handful of instructions a byte and the
 * interpreting one an iOS app embeds runs at under two megabytes a second, a thread dump of a build there
 * shows little else. So the app supplies the system's implementations, and this puts them first among the
 * security providers, where `MessageDigest.getInstance` finds them before the JDK's own.
 *
 * The state of a digest in progress lives in a Java array the native side reads and writes back, so there is
 * no native memory to free, and small updates are gathered here so a caller feeding single bytes does not
 * cross into native code for each.
 */
object NativeDigests {
    const val SHA256 = 0
    const val SHA1 = 1
    const val MD5 = 2

    /** Bytes of native state per algorithm; generous, the native side checks it fits. */
    const val STATE_BYTES = 256

    @JvmStatic external fun init(algorithm: Int, state: ByteArray)
    @JvmStatic external fun update(algorithm: Int, state: ByteArray, input: ByteArray, offset: Int, length: Int)
    @JvmStatic external fun finish(algorithm: Int, state: ByteArray): ByteArray

    fun install() {
        if (System.getProperty("codeassist.digests.native") != "true" || Security.getProvider(NAME) != null) return
        // Not offered at all unless the natives answer, and answer right: a wrong digest would be far worse
        // than a slow one.
        val works = runCatching {
            val state = ByteArray(STATE_BYTES)
            init(SHA256, state)
            update(SHA256, state, "abc".toByteArray(), 0, 3)
            finish(SHA256, state).joinToString("") { "%02x".format(it.toInt() and 0xFF) } ==
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        }.getOrDefault(false)
        if (!works) { System.err.println("native digests are not available; the JDK's are used"); return }
        Security.insertProviderAt(NativeProvider(), 1)
    }

    private const val NAME = "CodeAssistNative"

    private class NativeProvider : Provider(NAME, "1.0", "SHA-256, SHA-1 and MD5 from the host platform") {
        init {
            put("MessageDigest.SHA-256", Sha256::class.java.name)
            put("MessageDigest.SHA-1", Sha1::class.java.name)
            put("MessageDigest.MD5", Md5::class.java.name)
            put("Alg.Alias.MessageDigest.SHA256", "SHA-256")
            put("Alg.Alias.MessageDigest.SHA", "SHA-1")
            put("Alg.Alias.MessageDigest.SHA1", "SHA-1")
        }
    }

    class Sha256 : Digest(SHA256, 32)
    class Sha1 : Digest(SHA1, 20)
    class Md5 : Digest(MD5, 16)

    open class Digest(private val algorithm: Int, private val length: Int) : MessageDigestSpi() {
        private val state = ByteArray(STATE_BYTES)
        private val pending = ByteArray(4096)
        private var held = 0

        init { init(algorithm, state) }

        override fun engineGetDigestLength(): Int = length

        override fun engineUpdate(input: Byte) {
            if (held == pending.size) flush()
            pending[held++] = input
        }

        override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
            if (len >= pending.size) {
                flush()
                update(algorithm, state, input, offset, len)
                return
            }
            if (held + len > pending.size) flush()
            System.arraycopy(input, offset, pending, held, len)
            held += len
        }

        override fun engineDigest(): ByteArray {
            flush()
            return finish(algorithm, state).also { engineReset() }
        }

        override fun engineReset() {
            held = 0
            init(algorithm, state)
        }

        private fun flush() {
            if (held > 0) update(algorithm, state, pending, 0, held)
            held = 0
        }
    }
}
