package dev.ide.android.support.tools

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * In-process keystore creation (Bouncy Castle, no `keytool`) must produce a PKCS12 the stock provider reads —
 * the exact path [ApksigSigner] takes at sign time. These run on the desktop JVM but exercise the same code
 * the device uses.
 */
class KeystoreCryptoTest {

    @Test
    fun `create then validate and inspect`() {
        withTempDir("ks-crypto") { dir ->
            val file = dir.resolve("release.jks")
            val r = KeystoreCrypto.create(
                file,
                KeystoreCreateSpec(
                    storePass = "storepass", keyAlias = "upload",
                    commonName = "Acme Inc", organization = "Acme", country = "US", validityYears = 30,
                ),
            )
            assertTrue(r.success, r.message)
            assertTrue(Files.isRegularFile(file))

            val v = KeystoreCrypto.validate(file, "storepass")
            assertTrue(v.valid, v.error)
            assertEquals(listOf("upload"), v.aliases)
            val cert = v.certs.single()
            assertTrue("Acme Inc" in cert.subject, "subject was ${cert.subject}")
            assertTrue(cert.sha256.contains(":") && cert.sha1.contains(":"))
            assertTrue(cert.validUntilEpochMs > cert.validFromEpochMs)

            // A wrong password fails cleanly (not valid, no exception escaping).
            assertFalse(KeystoreCrypto.validate(file, "nope").valid)
        }
    }

    @Test
    fun `created keystore is consumable by the signer PKCS12 path`() {
        withTempDir("ks-signer") { dir ->
            val file = dir.resolve("k.jks")
            assertTrue(KeystoreCrypto.create(file, KeystoreCreateSpec("secretpw", "k0", "Test")).success)
            // Exactly what ApksigSigner does: stock PKCS12 read → getKey → getCertificateChain (one password).
            val ks = KeyStore.getInstance("PKCS12")
            Files.newInputStream(file).use { ks.load(it, "secretpw".toCharArray()) }
            assertTrue(ks.getKey("k0", "secretpw".toCharArray()) is PrivateKey)
            val chain = ks.getCertificateChain("k0")
            assertTrue(chain != null && chain.isNotEmpty() && chain[0] is X509Certificate)
        }
    }

    @Test
    fun `a modern PBES2 keystore imports into one the signer can read`() {
        withTempDir("ks-pbes2") { dir ->
            val legacy = dir.resolve("legacy.jks")
            assertTrue(KeystoreCrypto.create(legacy, KeystoreCreateSpec("storepass", "upload", "Acme Inc")).success)
            val modern = writeModernPkcs12(dir.resolve("modern.p12"), legacy, "storepass", "upload")
            // The fixture must really be the format that fails on ART: PBES2 + PBKDF2 around the key.
            assertTrue(contains(modern, PBES2_OID), "fixture is not a PBES2 keystore")

            val dest = dir.resolve("imported.p12")
            val copy = KeystoreCrypto.copyForSigning(modern, dest, "storepass", "upload", "")
            assertTrue(copy.success, copy.message)
            // On the desktop JVM the stock provider decrypts PBES2, so the copy is kept as it is; on ART it
            // cannot, and the same call converts (covered deterministically by the BKS case below).
            if (copy.converted) assertNoPbkdf2(dest)
            assertEquals("storepass", copy.keyPass, "PKCS12 protects the key with the store password")

            // The signer's exact path reads it, and it is the same key: same certificate fingerprint.
            assertTrue(stockKey(dest, "storepass", "upload") is PrivateKey)
            assertEquals(
                KeystoreCrypto.inspect(legacy, "storepass", "upload")?.sha256,
                KeystoreCrypto.inspect(dest, "storepass", "upload")?.sha256,
            )
        }
    }

    @Test
    fun `a keystore only Bouncy Castle can read still validates and imports`() {
        withTempDir("ks-bks") { dir ->
            val legacy = dir.resolve("legacy.jks")
            assertTrue(KeystoreCrypto.create(legacy, KeystoreCreateSpec("storepass", "upload", "Acme Inc")).success)
            // BKS stands in for the ART case: a format the platform provider cannot open but Bouncy Castle can.
            val bks = writeBks(dir.resolve("keys.bks"), legacy, "storepass", "upload")
            assertFalse(runCatching { KeyStore.getInstance("BKS") }.isSuccess, "the JVM must not read BKS itself")

            val validation = KeystoreCrypto.validate(bks, "storepass")
            assertTrue(validation.valid, validation.error)
            assertEquals(listOf("upload"), validation.aliases)

            val dest = dir.resolve("imported.p12")
            val copy = KeystoreCrypto.copyForSigning(bks, dest, "storepass", "upload", "storepass")
            assertTrue(copy.success, copy.message)
            assertTrue(copy.converted, "a store the signer cannot open has to be rewritten")
            assertNoPbkdf2(dest)
            assertTrue(stockKey(dest, "storepass", "upload") is PrivateKey, "the converted copy is not stock-readable")
            val material = assertNotNull(KeystoreCrypto.signingKey(dest, "storepass", "upload"))
            assertTrue(material.chain.isNotEmpty())
            assertEquals(
                KeystoreCrypto.inspect(legacy, "storepass", "upload")?.sha256,
                KeystoreCrypto.inspect(dest, "storepass", "upload")?.sha256,
            )
        }
    }

    @Test
    fun `a keystore the signer already reads is imported byte for byte`() {
        withTempDir("ks-verbatim") { dir ->
            val legacy = dir.resolve("legacy.jks")
            assertTrue(KeystoreCrypto.create(legacy, KeystoreCreateSpec("storepass", "upload", "Acme Inc")).success)
            val dest = dir.resolve("imported.jks")
            val copy = KeystoreCrypto.copyForSigning(legacy, dest, "storepass", "upload", "")
            assertTrue(copy.success, copy.message)
            assertFalse(copy.converted)
            assertContentEquals(Files.readAllBytes(legacy), Files.readAllBytes(dest))
        }
    }

    @Test
    fun `import reports a key that the password does not unlock`() {
        withTempDir("ks-locked") { dir ->
            val legacy = dir.resolve("legacy.jks")
            assertTrue(KeystoreCrypto.create(legacy, KeystoreCreateSpec("storepass", "upload", "Acme Inc")).success)
            val copy = KeystoreCrypto.copyForSigning(legacy, dir.resolve("out.jks"), "wrong-password", "upload", "")
            assertFalse(copy.success)
            assertTrue(copy.message.isNotBlank())
        }
    }

    // ---- fixtures ----

    /** DER for the PKCS#5 OIDs that ART cannot decrypt: `1.2.840.113549.1.5.13` / `.12`. */
    private val PBES2_OID = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x05, 0x0D)
    private val PBKDF2_OID = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x05, 0x0C)

    /** ART has no PBKDF2 `SecretKeyFactory`, so neither PKCS#5 scheme may appear in a keystore it must open. */
    private fun assertNoPbkdf2(file: Path) {
        assertFalse(contains(file, PBES2_OID), "$file still uses PBES2")
        assertFalse(contains(file, PBKDF2_OID), "$file still uses PBKDF2")
    }

    /** Reads the key the way [ApksigSigner] used to: the platform PKCS12 provider, one password. */
    private fun stockKey(file: Path, pass: String, alias: String): java.security.Key? {
        val ks = KeyStore.getInstance("PKCS12")
        Files.newInputStream(file).use { ks.load(it, pass.toCharArray()) }
        return ks.getKey(alias, pass.toCharArray())
    }

    private fun contains(file: Path, needle: ByteArray): Boolean {
        val bytes = Files.readAllBytes(file)
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    /** Re-write [source] as the PKCS12 a JDK 12+ `keytool` produces: AES-256/PBKDF2 around key and certs. */
    private fun writeModernPkcs12(dest: Path, source: Path, pass: String, alias: String): Path {
        val props = mapOf(
            "keystore.pkcs12.keyProtectionAlgorithm" to "PBEWithHmacSHA256AndAES_256",
            "keystore.pkcs12.certProtectionAlgorithm" to "PBEWithHmacSHA256AndAES_256",
            "keystore.pkcs12.macAlgorithm" to "HmacPBESHA256",
        )
        val previous = props.keys.associateWith { System.getProperty(it) }
        props.forEach { (k, v) -> System.setProperty(k, v) }
        try {
            copyEntry(source, dest, pass, alias, KeyStore.getInstance("PKCS12"))
        } finally {
            previous.forEach { (k, v) -> if (v == null) System.clearProperty(k) else System.setProperty(k, v) }
        }
        return dest
    }

    private fun writeBks(dest: Path, source: Path, pass: String, alias: String): Path {
        copyEntry(source, dest, pass, alias, KeyStore.getInstance("BKS", BouncyCastleProvider()))
        return dest
    }

    private fun copyEntry(source: Path, dest: Path, pass: String, alias: String, out: KeyStore) {
        val src = KeyStore.getInstance("PKCS12")
        Files.newInputStream(source).use { src.load(it, pass.toCharArray()) }
        out.load(null, null)
        out.setKeyEntry(alias, src.getKey(alias, pass.toCharArray()), pass.toCharArray(), src.getCertificateChain(alias))
        Files.newOutputStream(dest).use { out.store(it, pass.toCharArray()) }
    }
}
