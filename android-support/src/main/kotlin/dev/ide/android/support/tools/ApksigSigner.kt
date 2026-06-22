package dev.ide.android.support.tools

import com.android.apksig.ApkSigner as ApksigApkSigner
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Signs in-process with the apksig library (`com.android.apksig.ApkSigner`), the on-device signing
 * path. apksigner is pure Java, so on ART it is statically linked and called directly rather than via
 * `java -jar apksigner.jar` ([ApkSignerTool] is the subprocess alternative for the desktop SDK). The key
 * material is loaded from the keystore through standard JCE, which works identically on the desktop JVM
 * and ART.
 *
 * Alignment is done by apksig itself (`setAlignmentPreserved(false)`): it positions every uncompressed
 * entry on a 4-byte boundary and shared libraries on a page boundary, then signs over the aligned bytes.
 * This removes the dependency on the native `zipalign` binary (which is not bundled on-device, and which
 * ART will not `exec` from most locations anyway). 4-byte alignment of the uncompressed `resources.arsc`
 * is mandatory for install on API 30+, so it is a correctness requirement, not just a load-time
 * optimization.
 */
class ApksigSigner : ApkSigner {

    @Suppress("DEPRECATION") // SignerConfig.Builder(name, key, certs) is the stable, widely-supported form
    override fun sign(unsigned: Path, signed: Path, config: SigningConfig): ToolResult {
        signed.parent?.let { Files.createDirectories(it) }
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            Files.newInputStream(config.keystore).use { ks.load(it, config.storePass.toCharArray()) }
            val key = ks.getKey(config.keyAlias, config.keyPass.toCharArray()) as PrivateKey
            val certs = ks.getCertificateChain(config.keyAlias).map { it as X509Certificate }
            val signer = ApksigApkSigner.SignerConfig.Builder("CERT", key, certs).build()
            ApksigApkSigner.Builder(listOf(signer))
                .setInputApk(unsigned.toFile())
                .setOutputApk(signed.toFile())
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                // Align uncompressed entries (4-byte; .so to page size); no native zipalign needed.
                .setAlignmentPreserved(false)
                .build()
                .sign()
            ToolResult.ok(listOf("apksig (in-process) signed + aligned -> ${signed.fileName}"))
        } catch (t: Throwable) {
            ToolResult(false, listOf("apksig in-process failed: ${t.message}"))
        }
    }
}
