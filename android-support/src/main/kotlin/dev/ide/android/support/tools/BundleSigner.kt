package dev.ide.android.support.tools

import com.android.apksig.ApkSigner as ApksigApkSigner
import java.nio.file.Files
import java.nio.file.Path

/**
 * Signs an Android App Bundle (`.aab`). Unlike an APK, an `.aab` supports only **JAR (v1) signing** — there
 * is no APK Signing Block — so both implementations do v1-only signing with the project keystore (the upload
 * key; Play re-signs with the managed app key on download). Mirrors the APK [ApkSigner] split: a subprocess
 * form for the desktop SDK ([JarsignerBundleSigner], the canonical `jarsigner`) and an in-process form for
 * ART ([ApksigBundleSigner], pure-Java apksig). [minApi] selects the v1 digest algorithm.
 */
fun interface BundleSigner {
    fun sign(aab: Path, signedAab: Path, config: SigningConfig, minApi: Int): ToolResult
}

/** Signs the `.aab` with the JDK's `jarsigner` (desktop). v1/JAR signing is exactly what an AAB needs. */
class JarsignerBundleSigner(private val jarsigner: Path) : BundleSigner {
    override fun sign(aab: Path, signedAab: Path, config: SigningConfig, minApi: Int): ToolResult {
        signedAab.parent?.let { Files.createDirectories(it) }
        val cmd = listOf(
            jarsigner.toString(),
            "-keystore", config.keystore.toString(),
            "-storepass", config.storePass,
            "-keypass", config.keyPass,
            "-sigalg", "SHA256withRSA",
            "-digestalg", "SHA-256",
            "-signedjar", signedAab.toString(),
            aab.toString(),
            config.keyAlias,
        )
        val r = Subprocess.run(cmd)
        return if (r.success) ToolResult.ok(r.log + "jarsigner signed ${signedAab.fileName}") else r
    }
}

/**
 * Signs the `.aab` in-process with apksig, v1 only (the on-device path). apksig's signer treats the input as
 * a zip and, with v2/v3 disabled and an explicit minSdk, performs plain JAR signing — no APK Signing Block —
 * which is the only scheme an AAB accepts. Reuses the apksig dependency the on-device APK signer already
 * bundles, so ART needs no `jarsigner`.
 */
class ApksigBundleSigner : BundleSigner {
    @Suppress("DEPRECATION")
    override fun sign(aab: Path, signedAab: Path, config: SigningConfig, minApi: Int): ToolResult {
        signedAab.parent?.let { Files.createDirectories(it) }
        return try {
            val material = KeystoreCrypto.signingKey(config.keystore, config.storePass, config.keyAlias, config.keyPass)
                ?: return ToolResult.fail("apksig bundle signing failed: could not read the key '${config.keyAlias}' from ${config.keystore.fileName}")
            val signer = ApksigApkSigner.SignerConfig.Builder("CERT", material.privateKey, material.chain).build()
            ApksigApkSigner.Builder(listOf(signer))
                .setInputApk(aab.toFile())
                .setOutputApk(signedAab.toFile())
                .setMinSdkVersion(minApi)               // skips parsing a (non-existent root) AndroidManifest
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(false)             // AABs support only JAR (v1) signing
                .setV3SigningEnabled(false)
                .build()
                .sign()
            ToolResult.ok(listOf("apksig (in-process) v1-signed ${signedAab.fileName}"))
        } catch (t: Throwable) {
            ToolResult.fail("apksig bundle signing failed: ${t.message}")
        }
    }
}
