package dev.ide.android.support.tools

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.Key
import java.security.KeyFactory
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.MessageDigest
import java.security.Provider
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.Locale

/**
 * A read-only reader for Sun's `JKS` keystore format, for platforms that do not provide one.
 *
 * ART ships PKCS12 and BKS only, so `KeyStore.getInstance("JKS")` throws there and a `.jks` written by
 * `keytool` (what every Android signing guide still produces) cannot be opened on device at all. Bouncy
 * Castle does not implement JKS either, so the format is parsed here and handed back as an ordinary
 * read-only [KeyStore]: [KeystoreCrypto] can then inspect it and convert it to PKCS12 on import.
 *
 * The container is a flat binary stream:
 * ```
 * u4 magic = 0xFEEDFEED      u4 version (1 or 2)      u4 entryCount
 * per entry: u4 tag (1 = private key, 2 = trusted certificate)
 *            utf alias       u8 creation time (epoch ms)
 *   tag 1:   u4 len + the protected key       u4 chainLength + that many certificates
 *   tag 2:   one certificate
 * per certificate: utf type ("X.509", version 2 only) + u4 len + DER
 * trailer: 20-byte SHA-1 of (password as UTF-16BE) + "Mighty Aphrodite" + every preceding byte
 * ```
 * The trailer is what detects a wrong store password. A private key is stored as an `EncryptedPrivateKeyInfo`
 * whose algorithm is Sun's proprietary key protector (OID `1.3.6.1.4.1.42.2.17.1.1`); its payload is
 * `salt || (pkcs8 XOR keystream) || SHA-1(password || pkcs8)`, where the keystream is the chain of SHA-1
 * digests `d(0) = SHA-1(password || salt)`, `d(n) = SHA-1(password || d(n-1))`. No cipher is involved, which
 * is why the format is superseded by PKCS12, and why it can be read without a provider.
 */
object JksKeyStore {

    /** True when [file] starts with the JKS magic, i.e. it is worth handing to [read]. */
    fun looksLikeJks(file: Path): Boolean = magicOf(file) == MAGIC

    /**
     * Parse [file] and return it as a read-only [KeyStore] of type `JKS`. Throws [IOException] when the file
     * is not a JKS or [storePass] does not verify its trailing digest.
     */
    fun read(file: Path, storePass: String): KeyStore {
        val store = ReadOnlyKeyStore(Spi())
        Files.newInputStream(file).use { store.load(it, storePass.toCharArray()) }
        return store
    }

    private const val MAGIC = 0xFEEDFEED.toInt()

    /** JCEKS shares the layout but encrypts keys with PBEWithMD5AndTripleDES, which is not implemented here. */
    private const val JCEKS_MAGIC = 0xCECECECE.toInt()

    private const val KEY_ENTRY = 1
    private const val TRUSTED_ENTRY = 2
    private const val SALT_LEN = 20
    private const val DIGEST_LEN = 20

    /** DER content of `1.3.6.1.4.1.42.2.17.1.1`, the "JavaSoft proprietary key-protection" algorithm. */
    private val KEY_PROTECTOR_OID = byteArrayOf(0x2B, 0x06, 0x01, 0x04, 0x01, 0x2A, 0x02, 0x11, 0x01, 0x01)

    private val APHRODITE = "Mighty Aphrodite".toByteArray(Charsets.UTF_8)

    private fun magicOf(file: Path): Int? = runCatching {
        Files.newInputStream(file).use { input ->
            val head = ByteArray(4)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) return@use null
                read += n
            }
            var magic = 0
            for (b in head) magic = (magic shl 8) or (b.toInt() and 0xFF)
            magic
        }
    }.getOrNull()

    /** A [KeyStore] over a fixed [KeyStoreSpi]; the base constructor that takes one is protected. */
    private class ReadOnlyKeyStore(spi: KeyStoreSpi) : KeyStore(spi, null as Provider?, "JKS")

    /** One parsed entry: a key entry keeps its still-protected bytes so the key password stays per-entry. */
    private class Entry(val date: Date, val protectedKey: ByteArray?, val chain: Array<Certificate>)

    private class Spi : KeyStoreSpi() {

        private val entries = LinkedHashMap<String, Entry>()

        override fun engineLoad(stream: InputStream?, password: CharArray?) {
            entries.clear()
            val bytes = stream?.readBytes() ?: return
            if (bytes.size < 12 + DIGEST_LEN) throw IOException("Not a JKS keystore: the file is too short.")

            val data = DataInputStream(ByteArrayInputStream(bytes))
            when (data.readInt()) {
                MAGIC -> Unit
                JCEKS_MAGIC -> throw IOException("JCEKS keystores are not supported. Convert the keystore to PKCS12.")
                else -> throw IOException("Not a JKS keystore.")
            }
            val version = data.readInt()
            if (version != 1 && version != 2) throw IOException("Unsupported JKS version: $version.")
            // Only once the file is known to be a JKS, so a wrong-format file is not reported as a bad password.
            if (password != null) verifyDigest(bytes, password)
            val certs = CertificateFactory.getInstance("X.509")
            val count = data.readInt()
            if (count < 0 || count > bytes.size) throw IOException("Corrupt JKS keystore: $count entries.")
            repeat(count) {
                val tag = data.readInt()
                // JKS aliases are case-insensitive: keytool stores them folded, and lookups fold to match.
                val alias = data.readUTF().lowercase(Locale.ROOT)
                val date = Date(data.readLong())
                entries[alias] = when (tag) {
                    KEY_ENTRY -> {
                        val protectedKey = readBlock(data, bytes.size)
                        val chainLength = data.readInt()
                        if (chainLength < 0 || chainLength > bytes.size) throw IOException("Corrupt JKS certificate chain.")
                        Entry(date, protectedKey, Array(chainLength) { readCert(data, version, certs, bytes.size) })
                    }
                    TRUSTED_ENTRY -> Entry(date, null, arrayOf(readCert(data, version, certs, bytes.size)))
                    else -> throw IOException("Unrecognized JKS entry type: $tag.")
                }
            }
        }

        override fun engineGetKey(alias: String, password: CharArray?): Key? {
            val protectedKey = entry(alias)?.protectedKey ?: return null
            if (password == null) throw UnrecoverableKeyException("A password is required to recover the key.")
            return privateKeyOf(recover(protectedKey, password))
        }

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? =
            entry(alias)?.takeIf { it.protectedKey != null }?.chain?.copyOf()

        override fun engineGetCertificate(alias: String): Certificate? = entry(alias)?.chain?.firstOrNull()

        override fun engineGetCreationDate(alias: String): Date? = entry(alias)?.date

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(entries.keys.toList())

        override fun engineContainsAlias(alias: String): Boolean = entry(alias) != null

        override fun engineSize(): Int = entries.size

        override fun engineIsKeyEntry(alias: String): Boolean = entry(alias)?.protectedKey != null

        override fun engineIsCertificateEntry(alias: String): Boolean =
            entry(alias)?.let { it.protectedKey == null } ?: false

        override fun engineGetCertificateAlias(cert: Certificate?): String? =
            entries.entries.firstOrNull { it.value.protectedKey == null && it.value.chain.firstOrNull() == cert }?.key

        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?): Unit = readOnly()

        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?): Unit = readOnly()

        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?): Unit = readOnly()

        override fun engineDeleteEntry(alias: String?): Unit = readOnly()

        override fun engineStore(stream: OutputStream?, password: CharArray?): Unit = readOnly()

        private fun readOnly(): Nothing =
            throw UnsupportedOperationException("JKS keystores are read-only here; write PKCS12 instead.")

        private fun entry(alias: String): Entry? = entries[alias.lowercase(Locale.ROOT)]

        // ---- format ----

        private fun readBlock(data: DataInputStream, limit: Int): ByteArray {
            val length = data.readInt()
            if (length < 0 || length > limit) throw IOException("Corrupt JKS entry.")
            val block = ByteArray(length)
            data.readFully(block)
            return block
        }

        private fun readCert(data: DataInputStream, version: Int, certs: CertificateFactory, limit: Int): Certificate {
            if (version == 2) {
                val type = data.readUTF()
                if (type != "X.509" && type != "X509") throw IOException("Unsupported certificate type: $type.")
            }
            return certs.generateCertificate(ByteArrayInputStream(readBlock(data, limit)))
        }

        /** The trailing SHA-1 over the store password and every preceding byte: this is the password check. */
        private fun verifyDigest(bytes: ByteArray, password: CharArray) {
            val md = MessageDigest.getInstance("SHA-1")
            md.update(passwordBytes(password))
            md.update(APHRODITE)
            md.update(bytes, 0, bytes.size - DIGEST_LEN)
            val stored = bytes.copyOfRange(bytes.size - DIGEST_LEN, bytes.size)
            if (!MessageDigest.isEqual(md.digest(), stored)) {
                throw IOException("The keystore password is wrong, or the file has been tampered with.")
            }
        }

        /** Sun's key protector: XOR against a chain of SHA-1 digests seeded with the salt, then a checksum. */
        private fun recover(protectedKey: ByteArray, password: CharArray): ByteArray {
            val encrypted = encryptedData(protectedKey)
            val keyLength = encrypted.size - SALT_LEN - DIGEST_LEN
            if (keyLength <= 0) throw UnrecoverableKeyException("The key entry is truncated.")
            val pwd = passwordBytes(password)
            val md = MessageDigest.getInstance("SHA-1")
            val plain = ByteArray(keyLength)
            var digest = encrypted.copyOfRange(0, SALT_LEN)
            var offset = 0
            while (offset < keyLength) {
                md.update(pwd)
                md.update(digest)
                digest = md.digest()
                for (i in 0 until minOf(DIGEST_LEN, keyLength - offset)) {
                    plain[offset + i] = (encrypted[SALT_LEN + offset + i].toInt() xor digest[i].toInt()).toByte()
                }
                offset += DIGEST_LEN
            }
            md.update(pwd)
            md.update(plain)
            val stored = encrypted.copyOfRange(encrypted.size - DIGEST_LEN, encrypted.size)
            if (!MessageDigest.isEqual(md.digest(), stored)) {
                throw UnrecoverableKeyException("Cannot recover the key: the password is wrong.")
            }
            return plain
        }

        /** The `encryptedData` of the entry's EncryptedPrivateKeyInfo, once its algorithm is confirmed. */
        private fun encryptedData(der: ByteArray): ByteArray {
            var pos = 0
            fun read(tag: Int): IntRange {
                if (pos >= der.size || (der[pos].toInt() and 0xFF) != tag) throw UnrecoverableKeyException("Malformed key entry.")
                pos++
                if (pos >= der.size) throw UnrecoverableKeyException("Malformed key entry.")
                var length = der[pos].toInt() and 0xFF
                pos++
                if (length and 0x80 != 0) {
                    val octets = length and 0x7F
                    if (octets == 0 || octets > 4 || pos + octets > der.size) throw UnrecoverableKeyException("Malformed key entry.")
                    length = 0
                    repeat(octets) { length = (length shl 8) or (der[pos++].toInt() and 0xFF) }
                }
                val end = pos + length
                if (length < 0 || end > der.size) throw UnrecoverableKeyException("Malformed key entry.")
                return pos until end
            }
            read(0x30)                                  // EncryptedPrivateKeyInfo
            val algorithm = read(0x30)                  // AlgorithmIdentifier
            val oid = read(0x06)
            if (!der.copyOfRange(oid.first, oid.last + 1).contentEquals(KEY_PROTECTOR_OID)) {
                throw UnrecoverableKeyException("The key entry uses an unsupported protection algorithm.")
            }
            pos = algorithm.last + 1                    // skip any algorithm parameters
            val data = read(0x04)
            return der.copyOfRange(data.first, data.last + 1)
        }

        /** JKS hashes the password as UTF-16BE, two bytes per character, high byte first. */
        private fun passwordBytes(password: CharArray): ByteArray {
            val out = ByteArray(password.size * 2)
            for (i in password.indices) {
                out[i * 2] = (password[i].code shr 8).toByte()
                out[i * 2 + 1] = password[i].code.toByte()
            }
            return out
        }

        /** The recovered bytes are a PKCS#8 blob; the algorithm is whichever key factory accepts them. */
        private fun privateKeyOf(pkcs8: ByteArray): Key {
            val spec = PKCS8EncodedKeySpec(pkcs8)
            for (algorithm in listOf("RSA", "EC", "DSA")) {
                val key = runCatching { KeyFactory.getInstance(algorithm).generatePrivate(spec) }.getOrNull()
                if (key != null) return key
            }
            throw UnrecoverableKeyException("The key uses an algorithm this platform cannot load.")
        }
    }
}
