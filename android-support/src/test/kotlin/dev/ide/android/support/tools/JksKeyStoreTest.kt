package dev.ide.android.support.tools

import dev.ide.testkit.withTempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hand-rolled JKS reader, checked against the JDK's own JKS provider on the same file: ART has no JKS
 * provider at all, so on device this parser is the only way a `keytool`-made keystore can be opened.
 */
class JksKeyStoreTest {

    @Test
    fun `reads the same entries as the platform JKS provider`() {
        withTempDir("jks-read") { dir ->
            val jks = writeJks(dir, storePass = "storepass", keyPass = "keypass")
            val reference = KeyStore.getInstance("JKS")
            Files.newInputStream(jks).use { reference.load(it, "storepass".toCharArray()) }

            val parsed = JksKeyStore.read(jks, "storepass")
            assertEquals(reference.aliases().toList().sorted(), parsed.aliases().toList().sorted())
            assertEquals(reference.size(), parsed.size())

            // The key entry: same PKCS#8 bytes and same chain, recovered under its own key password.
            assertTrue(parsed.isKeyEntry("upload"))
            assertFalse(parsed.isCertificateEntry("upload"))
            val expected = reference.getKey("upload", "keypass".toCharArray()) as PrivateKey
            val actual = assertNotNull(parsed.getKey("upload", "keypass".toCharArray())) as PrivateKey
            assertEquals(expected.algorithm, actual.algorithm)
            assertContentEquals(expected.encoded, actual.encoded)
            val expectedChain = reference.getCertificateChain("upload").map { (it as X509Certificate).encoded }
            val actualChain = parsed.getCertificateChain("upload").map { (it as X509Certificate).encoded }
            assertEquals(expectedChain.size, actualChain.size)
            expectedChain.forEachIndexed { i, der -> assertContentEquals(der, actualChain[i]) }

            // The trusted-certificate entry keeps its kind, and has no chain.
            assertTrue(parsed.isCertificateEntry("ca"))
            assertFalse(parsed.isKeyEntry("ca"))
            assertNull(parsed.getCertificateChain("ca"))
            assertContentEquals(
                (reference.getCertificate("ca") as X509Certificate).encoded,
                (parsed.getCertificate("ca") as X509Certificate).encoded,
            )
            assertEquals("ca", parsed.getCertificateAlias(parsed.getCertificate("ca")))

            // JKS folds aliases, so a lookup in the case the user typed still resolves (the JDK does the same).
            assertTrue(parsed.containsAlias("Upload"))
            assertNotNull(parsed.getKey("UPLOAD", "keypass".toCharArray()))
        }
    }

    @Test
    fun `rejects a wrong store password and a wrong key password`() {
        withTempDir("jks-passwords") { dir ->
            val jks = writeJks(dir, storePass = "storepass", keyPass = "keypass")
            assertFailsWith<IOException> { JksKeyStore.read(jks, "not-the-password") }

            val parsed = JksKeyStore.read(jks, "storepass")
            assertFailsWith<UnrecoverableKeyException> { parsed.getKey("upload", "not-the-password".toCharArray()) }
        }
    }

    @Test
    fun `only claims files that really are JKS`() {
        withTempDir("jks-magic") { dir ->
            val pkcs12 = dir.resolve("keystore.p12")
            assertTrue(KeystoreCrypto.create(pkcs12, KeystoreCreateSpec("storepass", "upload", "Acme Inc")).success)
            assertFalse(JksKeyStore.looksLikeJks(pkcs12))
            assertFailsWith<IOException> { JksKeyStore.read(pkcs12, "storepass") }

            val jks = writeJks(dir, storePass = "storepass", keyPass = "keypass")
            assertTrue(JksKeyStore.looksLikeJks(jks))
        }
    }

    @Test
    fun `a JKS imports as a PKCS12 the signer can read`() {
        withTempDir("jks-import") { dir ->
            val jks = writeJks(dir, storePass = "storepass", keyPass = "keypass")
            val dest = dir.resolve("imported.jks")
            val copy = KeystoreCrypto.copyForSigning(jks, dest, "storepass", "upload", "keypass")
            assertTrue(copy.success, copy.message)
            assertTrue(copy.converted, "a JKS is never what the PKCS12 signer reads")
            // A converted copy is keyed by the store password, which is what the registry then records.
            assertEquals("storepass", copy.keyPass)

            val signer = KeyStore.getInstance("PKCS12")
            Files.newInputStream(dest).use { signer.load(it, "storepass".toCharArray()) }
            assertTrue(signer.getKey("upload", "storepass".toCharArray()) is PrivateKey)
            assertTrue(signer.getCertificateChain("upload").isNotEmpty(), "the chain travels with the key")
            assertEquals(
                KeystoreCrypto.inspect(jks, "storepass", "upload")?.sha256,
                KeystoreCrypto.inspect(dest, "storepass", "upload")?.sha256,
                "converting preserves the signing identity",
            )
        }
    }

    /**
     * A JKS written by the platform provider: one key entry under its own key password (JKS, unlike PKCS12,
     * really does keep them apart) plus a trusted certificate entry.
     */
    private fun writeJks(dir: Path, storePass: String, keyPass: String): Path {
        val source = dir.resolve("source.p12")
        assertTrue(KeystoreCrypto.create(source, KeystoreCreateSpec(storePass, "upload", "Acme Inc")).success)
        val p12 = KeyStore.getInstance("PKCS12")
        Files.newInputStream(source).use { p12.load(it, storePass.toCharArray()) }

        val jks = KeyStore.getInstance("JKS")
        jks.load(null, null)
        jks.setKeyEntry(
            "upload",
            p12.getKey("upload", storePass.toCharArray()),
            keyPass.toCharArray(),
            p12.getCertificateChain("upload"),
        )
        jks.setCertificateEntry("ca", p12.getCertificate("upload"))
        val dest = dir.resolve("keystore.jks")
        Files.newOutputStream(dest).use { jks.store(it, storePass.toCharArray()) }
        return dest
    }
}
