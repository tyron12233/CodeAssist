package dev.ide.android.support

import dev.ide.android.support.tasks.RBytecodeGenerator
import dev.ide.android.support.tasks.mergeResourceDirs
import dev.ide.android.support.tools.Aapt2
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.stream.Collectors

/**
 * Relinks a PREVIEW-scoped `resources.ap_` reflecting the LIVE editor buffer, for the real-view layout
 * preview. It self-builds a resource base from the project's live `res/` (merged like the build, so values
 * dedupe), then links the live edited layout on top as an aapt2 `-R` overlay (with `--auto-add-overlay`), so
 * an edited or newly added layout overrides/adds to the table. Building the base from the live tree means the
 * preview does NOT require a prior successful build (unlike reusing the build's `resources.ap_`), and it
 * reflects edits to other resources; the build's already-compiled flats are a fallback when the self-build
 * fails (e.g. a broken unrelated resource file).
 *
 * The base archive is cached by the live `res/` fingerprint, so editing a layout (an unsaved buffer leaves the
 * on-disk res unchanged) reuses it and only the one-file overlay recompiles. The per-edit ARSC relink is cached
 * by base + manifest + buffer and does NOT pass `--extra-packages` (that is the slow part).
 *
 * The preview `R.jar` ([Result.rJar]) — app + every `extraPackages` package, packaged to bytecode via
 * [RBytecodeGenerator] (the build's path) so a library/framework view resolving its own `R` at inflate time
 * (e.g. `androidx.coordinatorlayout.R$attr`) finds it (AARs do not ship their `R`) — is generated in a SEPARATE
 * step cached by base + manifest + extraPackages, i.e. by the resource SET, NOT the live buffer. Emitting a full
 * `R` per dependency package is expensive; keeping it off the per-keystroke relink is what keeps layout editing
 * fast (it regenerates only when a resource is saved or a dependency changes). This makes the real-view preview
 * self-sufficient: it no longer depends on a prior build's (id-mismatched, or entirely absent) `R.jar`.
 * On any failure [link] returns an [Result.error] and the caller falls back to owned rendering.
 */
class PreviewResourceLinker(
    private val aapt2: Aapt2,
    private val androidJar: Path,
    private val cacheRoot: Path,
) {
    /** A preview relink result: the linked preview [resourcesAp] + the generated preview [rJar] (app + extra
     *  packages, ids matching this arsc), or an [error] to fall back on. */
    class Result(val resourcesAp: Path?, val error: String? = null, val rJar: Path? = null)

    /**
     * Relink [editedLayout] (rendered from [liveText], not its on-disk content) over the project's live
     * resources [resDirs] (ascending priority, as [dev.ide.android.support.resources.AndroidResources.resourceDirs]
     * returns them), against [manifest]. Returns the preview `resources.ap_`, or an error to fall back on.
     */
    fun link(
        module: Module,
        variant: String,
        resDirs: List<Path>,
        editedLayout: Path,
        liveText: String,
        manifest: Path,
        packageName: String,
        minSdk: Int,
        targetSdk: Int,
        /** Dependency-lib + AAR package names to also emit `R` for (aapt2 `--extra-packages`), so a library view
         *  resolves its own `R` at inflate time. Mirrors the build's extra packages. */
        extraPackages: List<String> = emptyList(),
        progress: (String) -> Unit = {},
    ): Result = runCatching { linkInternal(module, variant, resDirs, editedLayout, liveText, manifest, packageName, minSdk, targetSdk, extraPackages, progress) }
        .getOrElse { Result(null, "${it.javaClass.simpleName}: ${it.message ?: ""}".trim()) }

    private fun linkInternal(
        module: Module,
        variant: String,
        resDirs: List<Path>,
        editedLayout: Path,
        liveText: String,
        manifest: Path,
        packageName: String,
        minSdk: Int,
        targetSdk: Int,
        extraPackages: List<String>,
        progress: (String) -> Unit,
    ): Result {
        if (!Files.exists(manifest)) return Result(null, "no manifest for the preview — build the project first")
        val outDir = cacheRoot.resolve(module.name).resolve(variant)
        Files.createDirectories(outDir)

        // Base: the live project resources, merged + compiled; fall back to the build's flats if that fails.
        val base = selfBuildBase(outDir, resDirs, progress)
            ?: fallbackBase(module, variant)
            ?: return Result(null, "no resources to link — fix resource errors or build the project once")

        // The preview R.jar (app + extra-package R classes) depends ONLY on the resource SET, not the live
        // layout buffer — editing a layout's XML doesn't change resource ids, so the R classes are stable. It's
        // the EXPENSIVE half (aapt2 emits a full R per --extra-package), so it's generated in its own step
        // ([ensurePreviewRJar]) cached by base + manifest + extraPackages, and reused across keystrokes.
        val previewRJar = ensurePreviewRJar(outDir, base, manifest, packageName, minSdk, targetSdk, extraPackages, progress)

        val previewAp = outDir.resolve("resources.ap_")
        val keyFile = outDir.resolve("key.txt")
        val key = cacheKey(base, manifest, editedLayout, liveText)
        if (Files.exists(previewAp) && readTextOrNull(keyFile) == key) return Result(previewAp, rJar = previewRJar)

        progress("Compiling resources")

        // Compile the live buffer as a one-file overlay res tree, mirroring the edited file's qualifier dir
        // (layout / layout-land / …) so aapt2 derives the right resource type + configuration.
        val qualifier = editedLayout.parent?.fileName?.toString() ?: "layout"
        val overlaySrc = outDir.resolve("overlay-src")
        val overlayCompiled = outDir.resolve("overlay-compiled")
        overlaySrc.toFile().deleteRecursively()
        overlayCompiled.toFile().deleteRecursively()
        val overlayFile = overlaySrc.resolve(qualifier).resolve(editedLayout.fileName.toString())
        Files.createDirectories(overlayFile.parent)
        Files.write(overlayFile, liveText.toByteArray())

        val overlay = aapt2.compile(listOf(overlaySrc), overlayCompiled)
        if (!overlay.result.success) {
            return Result(null, "aapt2 compile failed: ${overlay.result.log.takeLast(2).joinToString(" / ").ifBlank { "(no diagnostics)" }}")
        }

        progress("Linking resources")
        // base archives first, the live layout as an `-R` overlay → it overrides the saved copy (and
        // `--auto-add-overlay` lets a newly added layout in), without recompiling the whole project. NO
        // --extra-packages here: emitting a full R per dependency package is the expensive part and lives in
        // [ensurePreviewRJar] (cached on the resource set), NOT on this per-keystroke relink.
        val linkRes = aapt2.link(
            compiled = base,
            manifest = manifest,
            androidJar = androidJar,
            customPackage = packageName,
            extraPackages = emptyList(),
            minSdk = minSdk,
            targetSdk = targetSdk,
            genJavaDir = outDir.resolve("gen"),
            outApk = previewAp,
            overlays = overlay.archives,
        )
        if (!linkRes.success) {
            return Result(null, "aapt2 link failed: ${linkRes.log.takeLast(3).joinToString(" / ").ifBlank { "(no diagnostics)" }}")
        }
        Files.write(keyFile, key.toByteArray())
        return Result(previewAp, rJar = previewRJar)
    }

    /**
     * Generate the preview R.jar (app + [extraPackages]) once per resource SET, cached by
     * base + manifest + extraPackages (NOT the live buffer). Runs a base-only aapt2 link (no live overlay) to
     * emit one `R.java` per package, packaged to bytecode via [RBytecodeGenerator]. This is the expensive part
     * of the preview (aapt2 emits a FULL R per package), so keeping it off the per-keystroke relink path is what
     * keeps layout editing fast. Returns the cached/regenerated R.jar, a stale one if regeneration fails, or null.
     */
    private fun ensurePreviewRJar(
        outDir: Path, base: List<Path>, manifest: Path, packageName: String,
        minSdk: Int, targetSdk: Int, extraPackages: List<String>, progress: (String) -> Unit,
    ): Path? {
        val previewRJar = outDir.resolve("preview-r.jar")
        val rKeyFile = outDir.resolve("r-key.txt")
        val rKey = rJarKey(base, manifest, extraPackages)
        if (Files.exists(previewRJar) && readTextOrNull(rKeyFile) == rKey) return previewRJar

        val regenerated = runCatching {
            progress("Generating R")
            val rLinkDir = outDir.resolve("r-link")
            val genDir = rLinkDir.resolve("gen")
            genDir.toFile().deleteRecursively()   // so a removed dependency's stale R.java can't linger
            Files.createDirectories(rLinkDir)
            val linkRes = aapt2.link(
                compiled = base,
                manifest = manifest,
                androidJar = androidJar,
                customPackage = packageName,
                extraPackages = extraPackages,
                minSdk = minSdk,
                targetSdk = targetSdk,
                genJavaDir = genDir,
                outApk = rLinkDir.resolve("r.ap_"),
                overlays = emptyList(),
            )
            if (!linkRes.success) return@runCatching null
            val jar = generateRJar(genDir, previewRJar) ?: return@runCatching null
            Files.write(rKeyFile, rKey.toByteArray())
            jar
        }.getOrNull()
        // On a regeneration failure keep any previously-generated R.jar (stale but usable) over nothing.
        return regenerated ?: previewRJar.takeIf { Files.exists(it) }
    }

    /** Package every `R.java` under [genDir] (app + `--extra-packages`) into [outJar] as bytecode; null if none. */
    private fun generateRJar(genDir: Path, outJar: Path): Path? {
        if (!Files.isDirectory(genDir)) return null
        val rSources = Files.walk(genDir).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString() == "R.java" }.collect(Collectors.toList())
        }
        if (rSources.isEmpty()) return null
        RBytecodeGenerator.writeJar(rSources, outJar)
        return outJar.takeIf { Files.exists(it) }
    }

    /**
     * Merge + compile the live [resDirs] into a base archive, cached by their content fingerprint (so editing
     * a layout — which leaves the on-disk res unchanged — reuses it and only the overlay recompiles). Returns
     * the compiled archive(s), or null if the merge/compile failed (→ caller falls back to the build's flats).
     */
    private fun selfBuildBase(outDir: Path, resDirs: List<Path>, progress: (String) -> Unit): List<Path>? {
        val baseDir = outDir.resolve("base")
        val compiledDir = baseDir.resolve("compiled")
        val keyFile = baseDir.resolve("res-key.txt")
        val fingerprint = fingerprint(resDirs)
        val cached = listZips(compiledDir)
        if (cached.isNotEmpty() && readTextOrNull(keyFile) == fingerprint) return cached
        progress("Merging resources")

        return runCatching {
            val merged = baseDir.resolve("merged")
            mergeResourceDirs(resDirs.filter { Files.isDirectory(it) }, merged)
            compiledDir.toFile().deleteRecursively()
            val r = aapt2.compile(listOf(merged), compiledDir)
            if (!r.result.success) return@runCatching null
            val zips = listZips(compiledDir)
            if (zips.isEmpty()) return@runCatching null
            Files.write(keyFile, fingerprint.toByteArray())
            zips
        }.getOrNull()
    }

    /** The build's compiled resource flats, if a prior build produced them — a stale-but-valid fallback base. */
    private fun fallbackBase(module: Module, variant: String): List<Path>? =
        listZips(AndroidBuildSystem.compiledResPath(module, variant)).takeIf { it.isNotEmpty() }

    private fun listZips(dir: Path): List<Path> =
        if (Files.isDirectory(dir)) Files.list(dir).use { s -> s.filter { it.toString().endsWith(".zip") }.sorted().collect(Collectors.toList()) }
        else emptyList()

    /** SHA-1 over the live res files (path + mtime + size) — stable while the on-disk tree is unchanged. */
    private fun fingerprint(resDirs: List<Path>): String {
        val md = MessageDigest.getInstance("SHA-1")
        resDirs.filter { Files.isDirectory(it) }.sortedBy { it.toString() }.forEach { dir ->
            Files.walk(dir).use { s ->
                s.filter { Files.isRegularFile(it) }.sorted().forEach { f ->
                    md.update(f.toString().toByteArray())
                    md.update(runCatching { Files.getLastModifiedTime(f).toMillis() }.getOrDefault(0L).toString().toByteArray())
                    md.update(runCatching { Files.size(f) }.getOrDefault(0L).toString().toByteArray())
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Keyed on the base archives + manifest + the live buffer, so the ARSC relink runs only when one changed.
     *  Excludes [extraPackages]: those affect only the R.jar ([rJarKey]), not the rendered resource table. */
    private fun cacheKey(base: List<Path>, manifest: Path, editedLayout: Path, liveText: String): String =
        digest {
            for (a in (base + manifest).sortedBy { it.toString() }) putFileSig(a)
            put(editedLayout.toString())
            update(liveText.toByteArray())
        }

    /** Keyed on the base archives + manifest + extra packages — the inputs the R classes depend on (NOT the
     *  live layout buffer), so the expensive R.jar is regenerated only when the resource SET / deps change. */
    private fun rJarKey(base: List<Path>, manifest: Path, extraPackages: List<String>): String =
        digest {
            for (a in (base + manifest).sortedBy { it.toString() }) putFileSig(a)
            extraPackages.sorted().forEach { put(it) }
        }

    /** Small SHA-1 hashing DSL shared by the cache keys. */
    private class Digest(val md: MessageDigest) {
        fun put(s: String) = md.update(s.toByteArray())
        fun update(b: ByteArray) = md.update(b)
        fun putFileSig(a: Path) {
            put(a.toString())
            put(runCatching { Files.getLastModifiedTime(a).toMillis() }.getOrDefault(0L).toString())
            put(runCatching { Files.size(a) }.getOrDefault(0L).toString())
        }
    }

    private inline fun digest(block: Digest.() -> Unit): String {
        val d = Digest(MessageDigest.getInstance("SHA-1"))
        d.block()
        return d.md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readTextOrNull(p: Path): String? =
        runCatching { Files.readAllBytes(p).decodeToString() }.getOrNull()
}
