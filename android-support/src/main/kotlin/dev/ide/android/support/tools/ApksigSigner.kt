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
 * Alignment is native (`zipalign`) and runs as a subprocess. The v2/v3 signatures sign over the
 * aligned bytes; alignment runs first and apksig copies entries verbatim, preserving the alignment.
 * On-device the `zipalign` prebuilt may be missing (or its `exec` blocked); since alignment is a load-time
 * optimization and not a correctness requirement, the unaligned APK is then signed directly and still
 * installs and runs.
 */
class ApksigSigner(private val zipalign: Path) : ApkSigner {

    @Suppress("DEPRECATION") // SignerConfig.Builder(name, key, certs) is the stable, widely-supported form
    override fun sign(unsigned: Path, signed: Path, config: SigningConfig): ToolResult {
        signed.parent?.let { Files.createDirectories(it) }
        val aligned = signed.resolveSibling("aligned-${signed.fileName}")
        val log = ArrayList<String>()

        // Align when the native binary is present; otherwise sign the unaligned APK (logged, still valid).
        val toSign: Path
        if (Files.exists(zipalign)) {
            val align = Subprocess.run(listOf(zipalign.toString(), "-f", "4", unsigned.toString(), aligned.toString()))
            log += align.log
            if (!align.success) return ToolResult(false, log)
            toSign = aligned
        } else {
            log += "zipalign unavailable (${zipalign.fileName}) — signing unaligned"
            toSign = unsigned
        }

        return try {
            val ks = KeyStore.getInstance("PKCS12")
            Files.newInputStream(config.keystore).use { ks.load(it, config.storePass.toCharArray()) }
            val key = ks.getKey(config.keyAlias, config.keyPass.toCharArray()) as PrivateKey
            val certs = ks.getCertificateChain(config.keyAlias).map { it as X509Certificate }
            val signer = ApksigApkSigner.SignerConfig.Builder("CERT", key, certs).build()
            ApksigApkSigner.Builder(listOf(signer))
                .setInputApk(toSign.toFile())
                .setOutputApk(signed.toFile())
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build()
                .sign()
            ToolResult.ok(log + "apksig (in-process) signed -> ${signed.fileName}")
        } catch (t: Throwable) {
            ToolResult(false, log + "apksig in-process failed: ${t.message}")
        } finally {
            runCatching { Files.deleteIfExists(aligned) }
        }
    }
}
