package dev.ide.android.support.tools

import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
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

/** A keystore's signing material: the private key and the certificate chain that vouches for it. */
data class KeystoreKey(val privateKey: PrivateKey, val chain: List<X509Certificate>)

/** The outcome of staging an existing keystore into the registry (see [KeystoreCrypto.copyForSigning]). */
data class KeystoreCopyResult(
    val success: Boolean,
    val message: String,
    /** The key password of the written copy: a converted copy uses the store password (the PKCS12 norm). */
    val keyPass: String,
    /** True when the source had to be rewritten as a legacy PKCS12 to be readable at sign time. */
    val converted: Boolean,
)

/**
 * In-process keystore crypto, with no `keytool` (absent on ART). [create] generates an RSA keypair + a
 * self-signed X.509 certificate (via Bouncy Castle) and writes a **legacy PKCS12** keystore, which the
 * platform's stock `KeyStore.getInstance("PKCS12")` reader (used by [ApksigSigner] at sign time and by the
 * desktop apksigner) accepts on both the JVM and ART. Modern PKCS12 does not load on ART: keystores written
 * by a JDK 12+ `keytool` (or `openssl pkcs12 -export`) encrypt the key with PBES2/PBKDF2, and the platform
 * provider has no PBKDF2 `SecretKeyFactory`, so the load fails with `exception unwrapping private key:
 * java.security.NoSuchAlgorithmException: 1.2.840.113549.1.5.12 SecretKeyFactory not available`.
 *
 * Reads therefore try the stock provider first (what the signer does), then the bundled Bouncy Castle, which
 * does implement PBES2, then [JksKeyStore] for a `.jks` (ART has no JKS provider and neither does Bouncy
 * Castle). Those fallbacks only make a keystore readable here, so [copyForSigning] converts anything the
 * stock reader cannot handle into a legacy PKCS12 as it is imported: the key and certificate bytes are
 * untouched (same signing identity, same fingerprints), only the password-based encryption around them
 * changes.
 */
object KeystoreCrypto {

    /** Generate a keypair + self-signed cert and write a PKCS12 keystore at [file]. */
    fun create(file: Path, spec: KeystoreCreateSpec): KeystoreOpResult {
        return try {
            val bc = bouncyCastle ?: return KeystoreOpResult(false, BC_MISSING)
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
        val opened = open(file, storePass)
        val ks = opened.store ?: return KeystoreValidation(false, null, emptyList(), emptyList(), openError(opened.error))
        val aliases = ks.aliases().toList()
        val certs = aliases.mapNotNull { certInfo(ks, it) }
        return KeystoreValidation(true, opened.type, aliases, certs, null)
    }

    /** The aliases in [file], or empty if it can't be opened. */
    fun aliases(file: Path, storePass: String): List<String> =
        open(file, storePass).store?.aliases()?.toList() ?: emptyList()

    /** The certificate summary for [alias] (or the first alias), or null if the keystore can't be read. */
    fun inspect(file: Path, storePass: String, alias: String? = null): KeystoreCertInfo? {
        val ks = open(file, storePass).store ?: return null
        val a = alias ?: ks.aliases().toList().firstOrNull() ?: return null
        return certInfo(ks, a)
    }

    /**
     * The private key + certificate chain for [alias], for a signer to hand to apksig. Reads with the stock
     * provider first and falls back to Bouncy Castle, so a keystore registered before it was normalized (or
     * one written by another tool) still signs. Null when the store cannot be opened or the alias holds no key.
     */
    fun signingKey(file: Path, storePass: String, alias: String, keyPass: String = storePass): KeystoreKey? {
        val ks = open(file, storePass).store ?: return null
        val key = privateKey(ks, alias, keyPass, storePass) ?: return null
        val chain = ks.getCertificateChain(alias)?.mapNotNull { it as? X509Certificate }.orEmpty()
        if (chain.isEmpty()) return null
        return KeystoreKey(key, chain)
    }

    /**
     * Stage [source] at [dest] in a form the signer can read: a straight copy when the stock provider already
     * reads it as a PKCS12 whose key unlocks, otherwise a rewrite as a legacy PKCS12 through Bouncy Castle.
     * The rewrite re-encrypts, it does not re-issue: the private key and certificates are carried over as they
     * are, so the signing identity and its fingerprints do not change. PKCS12 protects the key with the store
     * password, so a rewritten copy reports [KeystoreCopyResult.keyPass] = [storePass].
     */
    fun copyForSigning(
        source: Path,
        dest: Path,
        storePass: String,
        keyAlias: String,
        keyPass: String,
    ): KeystoreCopyResult {
        if (!Files.isRegularFile(source)) return KeystoreCopyResult(false, "No such file: $source", keyPass, false)
        val opened = open(source, storePass)
        val ks = opened.store ?: return KeystoreCopyResult(false, openError(opened.error), keyPass, false)
        if (!ks.containsAlias(keyAlias)) {
            return KeystoreCopyResult(false, "Alias '$keyAlias' not found in ${source.fileName}.", keyPass, false)
        }
        return try {
            dest.parent?.let { Files.createDirectories(it) }
            // A stock-readable PKCS12 whose key unlocks is already what the signer expects: copy it verbatim.
            // The file itself has to be a PKCS12: the desktop JVM opens a JKS under the PKCS12 type too
            // (`keystore.type.compat`), and ART has no such compatibility.
            if (opened.stock && opened.type == PKCS12 && !JksKeyStore.looksLikeJks(source)) {
                val unlocked = passwords(keyPass, storePass).firstOrNull { privateKey(ks, keyAlias, it) != null }
                if (unlocked != null) {
                    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
                    return KeystoreCopyResult(true, "Imported ${source.fileName}", unlocked, false)
                }
            }
            convertToLegacyPkcs12(ks, dest, storePass, keyAlias, keyPass)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(dest) }
            KeystoreCopyResult(false, "Could not import ${source.fileName}: ${t.message ?: t::class.simpleName}", keyPass, false)
        }
    }

    // ---- internals ----

    private const val PKCS12 = "PKCS12"

    private const val BC_MISSING = "Keystore support is unavailable: Bouncy Castle is not on the classpath."

    /**
     * The bundled Bouncy Castle provider, or null on a host that does not ship it (it is a `compileOnly`
     * dependency here). It is passed explicitly to `KeyStore.getInstance` rather than installed into
     * [java.security.Security]: ART already registers an older, repackaged provider under the name "BC".
     */
    private val bouncyCastle: Provider? by lazy { runCatching { BouncyCastleProvider() }.getOrNull() }

    /** A keystore that opened: which type read it, whether the stock provider did, else the first failure. */
    private class Opened(val store: KeyStore?, val type: String?, val stock: Boolean, val error: String?)

    /**
     * Open [file], preferring the stock provider (what the signer uses) and falling back to Bouncy Castle for
     * the formats the platform cannot decrypt on its own, notably a PBES2/PBKDF2-protected PKCS12 on ART.
     * A JKS is read by [JksKeyStore] where no provider offers the type, which on ART is always.
     */
    private fun open(file: Path, storePass: String): Opened {
        var firstError: String? = null
        fun attempt(type: String, provider: Provider?): Opened? = try {
            val ks = if (provider == null) KeyStore.getInstance(type) else KeyStore.getInstance(type, provider)
            Files.newInputStream(file).use { ks.load(it, storePass.toCharArray()) }
            Opened(ks, type, provider == null, null)
        } catch (t: Throwable) {
            if (firstError == null) firstError = t.message ?: t::class.simpleName
            null
        }
        // PKCS12 everywhere; JKS only on the desktop JVM; BKS only on ART.
        for (type in listOf(PKCS12, "JKS", "BKS")) attempt(type, null)?.let { return it }
        bouncyCastle?.let { bc -> for (type in listOf(PKCS12, "BKS")) attempt(type, bc)?.let { return it } }
        // The file identifies itself as a JKS, so its own failure describes it better than the PKCS12 attempt.
        if (JksKeyStore.looksLikeJks(file)) {
            return try {
                Opened(JksKeyStore.read(file, storePass), "JKS", false, null)
            } catch (t: Throwable) {
                Opened(null, null, false, t.message ?: t::class.simpleName)
            }
        }
        return Opened(null, null, false, firstError)
    }

    private fun openError(raw: String?): String =
        "Could not open the keystore (wrong password, or a format this device cannot read)" +
            (raw?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")

    /** The passwords worth trying for a key entry: the given one, then the store's (the PKCS12 convention). */
    private fun passwords(keyPass: String, storePass: String): List<String> =
        (listOf(keyPass, storePass).filter { it.isNotEmpty() } + storePass).distinct()

    /** The key under [alias], trying each candidate password (a wrong one throws, which reads as "no key"). */
    private fun privateKey(ks: KeyStore, alias: String, vararg candidates: String): PrivateKey? {
        for (p in candidates.distinct()) {
            val key = runCatching { ks.getKey(alias, p.toCharArray()) }.getOrNull()
            if (key is PrivateKey) return key
        }
        return null
    }

    /**
     * Rewrite the entries of [ks] into a legacy PKCS12 (SHA1/3DES) at [dest], keyed by the store password.
     * Key entries locked with some other password are dropped, so the requested [keyAlias] must survive; the
     * result is re-read through the stock provider before it is accepted, since that is the reader that has
     * to open it at sign time.
     */
    private fun convertToLegacyPkcs12(
        ks: KeyStore,
        dest: Path,
        storePass: String,
        keyAlias: String,
        keyPass: String,
    ): KeystoreCopyResult {
        val bc = bouncyCastle ?: return KeystoreCopyResult(false, BC_MISSING, keyPass, false)
        val out = KeyStore.getInstance(PKCS12, bc)
        out.load(null, null)
        for (alias in ks.aliases().toList()) {
            if (ks.isCertificateEntry(alias)) {
                ks.getCertificate(alias)?.let { out.setCertificateEntry(alias, it) }
                continue
            }
            val key = privateKey(ks, alias, keyPass, storePass) ?: continue
            val chain = ks.getCertificateChain(alias) ?: continue
            out.setKeyEntry(alias, key, storePass.toCharArray(), chain)
        }
        if (!out.containsAlias(keyAlias)) {
            return KeystoreCopyResult(false, "The key '$keyAlias' could not be unlocked with the password given.", keyPass, false)
        }
        Files.newOutputStream(dest).use { out.store(it, storePass.toCharArray()) }

        val check = KeyStore.getInstance(PKCS12)
        Files.newInputStream(dest).use { check.load(it, storePass.toCharArray()) }
        if (privateKey(check, keyAlias, storePass) == null) {
            runCatching { Files.deleteIfExists(dest) }
            return KeystoreCopyResult(false, "The converted keystore could not be read back.", keyPass, false)
        }
        return KeystoreCopyResult(true, "Imported ${dest.fileName} (converted to PKCS12)", storePass, true)
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
