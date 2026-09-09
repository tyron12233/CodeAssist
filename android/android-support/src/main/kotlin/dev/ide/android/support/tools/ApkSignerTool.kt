package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path

/**
 * Aligns and signs an APK. `zipalign` (native) 4-byte aligns
 * the archive so the platform can mmap entries; `apksigner` (pure Java, launched as `java -jar
 * apksigner.jar`) applies the v1/v2 JAR + APK Signature Scheme. Run together because v2 signs over the
 * aligned bytes — aligning after signing would break the signature.
 */
class ApkSignerTool(
    private val apksignerJar: Path,
    private val zipalign: Path,
    private val javaLauncher: Path,
) : ApkSigner {

    override fun sign(unsigned: Path, signed: Path, config: SigningConfig): ToolResult {
        signed.parent?.let { Files.createDirectories(it) }
        val aligned = signed.resolveSibling("aligned-${signed.fileName}")
        val log = ArrayList<String>()

        val align = Subprocess.run(listOf(zipalign.toString(), "-f", "4", unsigned.toString(), aligned.toString()))
        log += align.log
        if (!align.success) return ToolResult(false, log)

        val sign = Subprocess.run(
            listOf(
                javaLauncher.toString(), "-jar", apksignerJar.toString(), "sign",
                "--ks", config.keystore.toString(),
                "--ks-pass", "pass:${config.storePass}",
                "--ks-key-alias", config.keyAlias,
                "--key-pass", "pass:${config.keyPass}",
                "--out", signed.toString(),
                aligned.toString(),
            ),
        )
        log += sign.log
        runCatching { Files.deleteIfExists(aligned) }
        return ToolResult(sign.success, log)
    }
}
