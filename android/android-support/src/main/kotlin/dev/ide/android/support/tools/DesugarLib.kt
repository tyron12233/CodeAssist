package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * The artifacts core-library desugaring needs, injected by the host (resolved from the build cache on the
 * desktop, bundled as assets on device): the desugar runtime ([runtimeJar] = `desugar_jdk_libs.jar`, which
 * L8 dexes into the APK) and the configuration jar ([configJar] = `desugar_jdk_libs_configuration.jar`,
 * which holds the `desugar.json` D8/R8/L8 read). Null on a host that does not ship them, in which case a
 * module's `coreLibraryDesugaringEnabled` is honored as a no-op (the build still runs, just un-desugared).
 */
data class DesugarLib(val runtimeJar: Path, val configJar: Path) {
    /**
     * Extract `META-INF/desugar/d8/desugar.json` from [configJar] into [out] (the form D8/R8/L8 read via
     * `--desugared-lib` / `addDesugaredLibraryConfiguration`), returning it; null when the entry is absent.
     */
    fun extractConfigJson(out: Path): Path? {
        ZipFile(configJar.toFile()).use { zf ->
            val e = zf.getEntry(CONFIG_ENTRY) ?: return null
            out.parent?.let { Files.createDirectories(it) }
            zf.getInputStream(e).use { ins -> Files.copy(ins, out, StandardCopyOption.REPLACE_EXISTING) }
        }
        return out
    }

    companion object {
        private const val CONFIG_ENTRY = "META-INF/desugar/d8/desugar.json"
    }
}
