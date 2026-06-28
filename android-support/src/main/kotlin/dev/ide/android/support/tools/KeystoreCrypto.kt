package dev.ide.android.support.tools

import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * What an `-genkeypair` invocation would specify: the key, its validity, and the certificate's distinguished
 * name. Note PKCS12 uses ONE password for both the store and the key (keytool ignores a separate `-keypass`
 * for PKCS12), so [storePass] protects the generated key too.
 */
data class KeystoreCreateSpec(
    val storePass: String,
    val keyAlias: String,
    /** Certificate distinguished-name fields (only CN is required; blanks are dropped). */
    val commonName: String,
    val organizationalUnit: String? = null,
    val organization: String? = null,
    val locality: String? = null,
    val state: String? = null,
    /** Two-letter country code (e.g. `US`). */
    val country: String? = null,
    val validityYears: Int = 25,
    val keySize: Int = 2048,
)

/** One certificate's human-facing summary (owner, issuer, validity window, fingerprints). */
data class KeystoreCertInfo(
    val alias: String,
    val subject: String,
    val issuer: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
    val sha1: String,
    val sha256: String,
)

/** The outcome of loading/validating a keystore: its type + entries when valid, or the error otherwise. */
data class KeystoreValidation(
    val valid: Boolean,
    val type: String?,
    val aliases: List<String>,
    val certs: List<KeystoreCertInfo>,
    val error: String?,
)

data class KeystoreOpResult(val success: Boolean, val message: String)

/**
 * In-process keystore crypto — no `keytool` (absent on ART). [create] generates an RSA keypair + a
 * self-signed X.509 certificate (via Bouncy Castle) and writes a **legacy PKCS12** keystore, which the
 * platform's stock `KeyStore.getInstance("PKCS12")` reader (used by [ApksigSigner] at sign time and by the
 * desktop apksigner) accepts on both the JVM and ART — modern (HmacPBESHA256) PKCS12 fails to verify on ART.
 *
 * [validate]/[inspect]/[aliases] only read, so they use the stock provider (no Bouncy Castle), mirroring what
 * the signer will do — a keystore that validates here is one the build can actually sign with.
 */
object KeystoreCrypto {

    /** Generate a keypair + self-signed cert and write a PKCS12 keystore at [file]. */
    fun create(file: Path, spec: KeystoreCreateSpec): KeystoreOpResult {
        return try {
            val bc = BouncyCastleProvider()
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(spec.keySize) }.generateKeyPair()

            val dn = X500NameBuilder(BCStyle.INSTANCE).apply {
                addRDN(BCStyle.CN, spec.commonName.ifBlank { "Unknown" })
                spec.organizationalUnit?.takeIf { it.isNotBlank() }?.let { addRDN(BCStyle.OU, it) }
                spec.organization?.takeIf { it.isNotBlank() }?.let { addRDN(BCStyle.O, it) }
                spec.locality?.takeIf { it.isNotBlank() }?.let { addRDN(BCStyle.L, it) }
                spec.state?.takeIf { it.isNotBlank() }?.let { addRDN(BCStyle.ST, it) }
                spec.country?.takeIf { it.isNotBlank() }?.let { addRDN(BCStyle.C, it) }
            }.build()

            val now = System.currentTimeMillis()
            val notAfter = now + spec.validityYears.coerceAtLeast(1) * 365L * 24 * 3600 * 1000
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val holder = JcaX509v3CertificateBuilder(
                dn, BigInteger.valueOf(now), Date(now), Date(notAfter), dn, keyPair.public,
            ).build(signer)
            val cert = JcaX509CertificateConverter().setProvider(bc).getCertificate(holder)

            file.parent?.let { Files.createDirectories(it) }
            // Write with Bouncy Castle's PKCS12 (classic SHA1/3DES), which the stock reader accepts on ART.
            val ks = KeyStore.getInstance("PKCS12", bc)
            ks.load(null, null)
            // PKCS12 protects the key with the store password (a separate key password isn't interoperable —
            // keytool ignores `-keypass` for PKCS12), so the stock reader can decrypt it with the same password.
            ks.setKeyEntry(spec.keyAlias, keyPair.private, spec.storePass.toCharArray(), arrayOf(cert))
            Files.newOutputStream(file).use { ks.store(it, spec.storePass.toCharArray()) }
            KeystoreOpResult(true, "Created ${file.fileName}")
        } catch (t: Throwable) {
            KeystoreOpResult(false, "Keystore creation failed: ${t.message ?: t::class.simpleName}")
        }
    }

    /** Load [file] with [storePass]; report its type + aliases + certs, or the failure (e.g. wrong password). */
    fun validate(file: Path, storePass: String): KeystoreValidation {
        if (!Files.isRegularFile(file)) return KeystoreValidation(false, null, emptyList(), emptyList(), "No such file: $file")
        val (ks, type, error) = load(file, storePass)
            ?: return KeystoreValidation(false, null, emptyList(), emptyList(), "Could not open keystore (wrong password or unsupported format).")
        if (ks == null) return KeystoreValidation(false, null, emptyList(), emptyList(), error)
        val aliases = ks.aliases().toList()
        val certs = aliases.mapNotNull { certInfo(ks, it) }
        return KeystoreValidation(true, type, aliases, certs, null)
    }

    /** The aliases in [file], or empty if it can't be opened. */
    fun aliases(file: Path, storePass: String): List<String> =
        load(file, storePass)?.first?.aliases()?.toList() ?: emptyList()

    /** The certificate summary for [alias] (or the first alias), or null if the keystore can't be read. */
    fun inspect(file: Path, storePass: String, alias: String? = null): KeystoreCertInfo? {
        val ks = load(file, storePass)?.first ?: return null
        val a = alias ?: ks.aliases().toList().firstOrNull() ?: return null
        return certInfo(ks, a)
    }

    // ---- internals ----

    /** Try the keystore types the platform supports (PKCS12 everywhere; JKS only on the desktop JVM). */
    private fun load(file: Path, storePass: String): Triple<KeyStore?, String?, String?>? {
        var firstError: String? = null
        for (type in listOf("PKCS12", "JKS", "BKS")) {
            try {
                val ks = KeyStore.getInstance(type)
                Files.newInputStream(file).use { ks.load(it, storePass.toCharArray()) }
                return Triple(ks, type, null)
            } catch (t: Throwable) {
                if (firstError == null) firstError = t.message ?: t::class.simpleName
            }
        }
        return Triple(null, null, firstError)
    }

    private fun certInfo(ks: KeyStore, alias: String): KeystoreCertInfo? {
        val cert = ks.getCertificate(alias) as? X509Certificate ?: return null
        val der = cert.encoded
        return KeystoreCertInfo(
            alias = alias,
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            validFromEpochMs = cert.notBefore.time,
            validUntilEpochMs = cert.notAfter.time,
            sha1 = fingerprint("SHA-1", der),
            sha256 = fingerprint("SHA-256", der),
        )
    }

    private fun fingerprint(algorithm: String, der: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(der).joinToString(":") { "%02X".format(it) }
}
