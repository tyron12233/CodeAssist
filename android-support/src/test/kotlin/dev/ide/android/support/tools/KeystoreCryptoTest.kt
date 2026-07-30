package dev.ide.android.support.tools

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
