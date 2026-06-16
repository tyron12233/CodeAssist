package dev.ide.core

import dev.ide.android.support.tools.HttpSdkNetFetcher
import dev.ide.android.support.tools.SdkNetFetcher
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import java.nio.file.Paths

/**
 * JDK source support for the editor's parameter-name + javadoc resolution. The running JDK usually ships its
 * sources as `lib/src.zip`; when it doesn't, a JDK that does (Eclipse Temurin) can be downloaded and only its
 * `src.zip` kept, copied to `<workspace>/.platform/jdk-src.zip`, which the analyzer adds to its source path.
 * Sources are attached for documentation only; the build JDK is not repointed.
 *
 * Downloads are a desktop concern (a downloaded desktop JVM cannot run on ART), so on-device this reports the
 * existing `src.zip` status only.
 */
class JdkManager(private val platformDir: Path, private val fetcher: SdkNetFetcher = HttpSdkNetFetcher) {

    data class Info(val home: String, val version: String, val srcZip: String?)

    /** The src.zip that's actually usable for docs: the running JDK's, or a previously downloaded override. */
    fun effectiveSrcZip(): Path? = currentSrcZip() ?: overrideSrcZip()

    fun info(): Info = Info(
        home = System.getProperty("java.home").orEmpty(),
        version = System.getProperty("java.version").orEmpty(),
        srcZip = effectiveSrcZip()?.toString(),
    )

    private fun currentSrcZip(): Path? =
        runCatching { Paths.get(System.getProperty("java.home")).resolve("lib").resolve("src.zip") }
            .getOrNull()?.takeIf { Files.isRegularFile(it) }

    /** A previously-downloaded JDK `src.zip` kept under `.platform`, if any. */
    fun overrideSrcZip(): Path? = platformDir.resolve("jdk-src.zip").takeIf { Files.isRegularFile(it) }

    /**
     * Download a Temurin JDK [feature] (e.g. 21), extract it, and keep its `src.zip` as the override so the
     * editor gets JDK sources. Returns null on success or an error message. Desktop only.
     */
    fun downloadJdkSources(feature: Int, onProgress: (Long, Long) -> Unit = { _, _ -> }): String? {
        val os = adoptiumOs() ?: return "Unsupported OS for JDK download."
        val arch = adoptiumArch()
        val ext = if (os == "windows") "zip" else "tar.gz"
        val url = "https://api.adoptium.net/v3/binary/latest/$feature/ga/$os/$arch/jdk/hotspot/normal/eclipse?project=jdk"
        val archive = Files.createTempFile("jdk-$feature", ".$ext")
        val staging = Files.createTempDirectory("jdk-extract")
        try {
            if (!fetcher.download(url, archive, onProgress)) return "JDK download failed."
            if (ext == "zip") unzip(archive, staging) else if (!untarGz(archive, staging)) return "Couldn't extract the JDK archive (tar unavailable)."
            val src = Files.walk(staging).use { s -> s.filter { it.fileName?.toString() == "src.zip" }.findFirst().orElse(null) }
                ?: return "Downloaded JDK has no src.zip."
            Files.createDirectories(platformDir)
            Files.copy(src, platformDir.resolve("jdk-src.zip"), StandardCopyOption.REPLACE_EXISTING)
            return null
        } catch (e: Exception) {
            return "JDK download failed: ${e.message}"
        } finally {
            runCatching { Files.deleteIfExists(archive) }
            runCatching { Files.walk(staging).use { it.sorted(Comparator.reverseOrder()).forEach { p -> Files.deleteIfExists(p) } } }
        }
    }

    private fun adoptiumOs(): String? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> "mac"
            os.contains("win") -> "windows"
            os.contains("nux") || os.contains("nix") -> "linux"
            else -> null
        }
    }

    private fun adoptiumArch(): String {
        val a = System.getProperty("os.arch").orEmpty().lowercase()
        return if (a.contains("aarch64") || a.contains("arm64")) "aarch64" else "x64"
    }

    private fun unzip(zip: Path, dest: Path) {
        Files.createDirectories(dest)
        ZipInputStream(BufferedInputStream(Files.newInputStream(zip))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val target = dest.resolve(e.name).normalize()
                if (target.startsWith(dest)) {
                    if (e.isDirectory) Files.createDirectories(target)
                    else { Files.createDirectories(target.parent); Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING) }
                }
                zis.closeEntry(); e = zis.nextEntry
            }
        }
    }

    /** Extract a .tar.gz via the system `tar` (present on macOS/Linux). Returns false if it isn't available. */
    private fun untarGz(archive: Path, dest: Path): Boolean = runCatching {
        Files.createDirectories(dest)
        val p = ProcessBuilder("tar", "-xzf", archive.toString(), "-C", dest.toString())
            .redirectErrorStream(true).start()
        p.inputStream.use { it.readBytes() }
        p.waitFor() == 0
    }.getOrDefault(false)
}
