package dev.ide.android.support

import dev.ide.android.support.tasks.RBytecodeGenerator
import dev.ide.android.support.tasks.ResourceMergeMap
import dev.ide.android.support.tasks.mergeResourceDirs
import dev.ide.android.support.tools.Aapt2
import dev.ide.android.support.tools.Aapt2Errors
import dev.ide.model.Module
import dev.ide.platform.log.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

/**
 * Relinks a PREVIEW-scoped `resources.ap_` reflecting the LIVE editor buffer, for the real-view layout
 * preview. It self-builds a resource base from the project's live `res/` (merged like the build, so values
 * dedupe), then links the live edited layout on top as an aapt2 `-R` overlay (with `--auto-add-overlay`), so
 * an edited or newly added layout overrides/adds to the table. Building the base from the live tree means the
 * preview does NOT require a prior successful build (unlike reusing the build's `resources.ap_`), and it
 * reflects edits to other resources.
 *
 * A resource error (a malformed file, a reference to a missing resource) comes back as
 * [Result.resourceErrors], located in the user's own file rather than the preview's merged copy, so the
 * preview shows it instead of a stale render. The build's already-compiled flats are a fallback only when the
 * self-build fails without aapt2 naming an error (the tool itself failing).
 *
 * The base archive is cached by the live `res/` files' signatures, ignoring the edited layout (the live buffer
 * always overlays it), so editing a layout, and saving it, reuses the base and only the one-file overlay
 * recompiles. The per-edit ARSC relink is cached by base + manifest + buffer, and an edit that changes no ids
 * skips it: the layout is linked alone against the last full output and patched into a copy of it.
 *
 * The preview `R.jar` ([Result.rJar]) holds the app's `R`, packaged to bytecode via [RBytecodeGenerator]. A
 * library view resolving its own `R` at inflate time (e.g. `androidx.coordinatorlayout.R$attr`; AARs do not
 * ship one) gets the app's: in a non-namespaced app every package's `R` is the same table, and the renderer
 * resolves each library `R` to the app's. It is built from the `R.java` the per-edit link writes and keyed on
 * that file's content, so it regenerates only when the resource ids change. This makes the real-view preview
 * self-sufficient: it no longer depends on a prior build's (id-mismatched, or entirely absent) `R.jar`.
 * On any failure [link] returns an [Result.error] and the caller falls back to owned rendering.
 *
 * Every link runs against a synthesized preview manifest ([previewManifest]), never the app's own. The render
 * reads only the resource table, and the app manifest made the link fail outright on a freshly imported
 * project: no merged manifest exists before the first build, and the source manifest of a modern project has
 * no `package` attribute (its namespace lives in the build script), or carries `${placeholder}` values and
 * references aapt2 rejects. The preview then fell back to the build's stale `resources.ap_`, or to owned
 * rendering, until a clean build produced a merged manifest.
 */
class PreviewResourceLinker(
    private val aapt2: Aapt2,
    private val androidJar: Path,
    private val cacheRoot: Path,
) {
    /** A preview relink result: the linked preview [resourcesAp] + the generated preview [rJar] (app + extra
     *  packages, ids matching this arsc), or an [error] to fall back on. Non-empty [resourceErrors] mean the
     *  project's resources are broken: [error] then summarizes them and [resourcesAp] is null. */
    class Result(
        val resourcesAp: Path?,
        val error: String? = null,
        val rJar: Path? = null,
        val resourceErrors: List<ResourceError> = emptyList(),
        /** Classes the preview interprets beside the project's own ([PreviewToolsClasses]), or null. */
        val toolsJar: Path? = null,
    )

    /** An aapt2 error in the project's resources: the user's [file] (null when aapt2 named none), its 1-based
     *  [line] (null when unknown) and aapt2's [message]. */
    data class ResourceError(val file: Path?, val line: Int?, val message: String)

    private val log = Log.logger("ide.preview")

    companion object {
        /** File name of the preview's `R` jar ([Result.rJar]); the renderer recognizes it on the classpath. */
        const val R_JAR_NAME = "preview-r.jar"

        /** What the last full link was linked from, and its size, for [quickRelink]. */
        private const val FULL_KEY = "full-key.txt"

        /** Dead bytes [quickRelink]'s patches may leave in `resources.ap_` before a full link compacts it. */
        private const val MAX_PATCH_GROWTH = 8L shl 20
    }

    /** Per output dir: the merge map of the current base, keyed by the res fingerprint it was merged from. */
    private val mergeMaps = ConcurrentHashMap<Path, Pair<String, ResourceMergeMap>>()

    /** Per output dir: the errors the base failed to compile with, keyed by res fingerprint, so an unchanged
     *  broken tree isn't recompiled on every preview request. */
    private val baseErrors = ConcurrentHashMap<Path, Pair<String, List<ResourceError>>>()

    /**
     * Relink [editedLayout] (rendered from [liveText], not its on-disk content) over the project's live
     * resources [resDirs] (ascending priority, as [dev.ide.android.support.resources.AndroidResources.resourceDirs]
     * returns them), under [packageName]. Returns the preview `resources.ap_`, or an error to fall back on.
     */
    fun link(
        module: Module,
        variant: String,
        resDirs: List<Path>,
        editedLayout: Path,
        liveText: String,
        packageName: String,
        minSdk: Int,
        targetSdk: Int,
        progress: (String) -> Unit = {},
    ): Result = runCatching { linkInternal(module, variant, resDirs, editedLayout, liveText, packageName, minSdk, targetSdk, progress) }
        .getOrElse { Result(null, "${it.javaClass.simpleName}: ${it.message ?: ""}".trim()) }

    private fun linkInternal(
        module: Module,
        variant: String,
        resDirs: List<Path>,
        editedLayout: Path,
        liveText: String,
        packageName: String,
        minSdk: Int,
        targetSdk: Int,
        progress: (String) -> Unit,
    ): Result {
        val outDir = cacheRoot.resolve(module.name).resolve(variant)
        Files.createDirectories(outDir)
        val manifest = previewManifest(outDir, packageName)
        val t0 = System.nanoTime()

        // Base: the live project resources, merged + compiled. A resource error is reported, not papered over
        // with the build's (stale) flats; those stand in only when aapt2 failed without naming an error.
        val base = when (val built = selfBuildBase(outDir, resDirs, editedLayout, progress)) {
            is Base.Built -> built.archives
            is Base.Broken -> return errors(built.errors)
            null -> fallbackBase(module, variant)
                ?: return Result(null, "no resources to link — build the project once")
        }
        val tBase = System.nanoTime()

        val previewAp = outDir.resolve("resources.ap_")
        val keyFile = outDir.resolve("key.txt")
        val key = cacheKey(base, manifest, editedLayout, liveText)
        var quick = false
        if (!Files.exists(previewAp) || readTextOrNull(keyFile) != key) {
            progress("Compiling resources")
            val rewritten = PreviewLayoutRewriter.rewrite(liveText, frameworkAttrs)
            val fullKey = fullLinkKey(base, manifest, editedLayout, packageName, minSdk, targetSdk)
            quick = runCatching { quickRelink(outDir, fullKey, manifest, editedLayout, rewritten, packageName, minSdk, targetSdk, previewAp) }
                .onFailure { log.warn("quick relink failed, linking in full: ${it.message}") }.getOrDefault(false)
            if (!quick) {
                // Compile the live buffer as a one-file overlay res tree, mirroring the edited file's qualifier dir
                // (layout / layout-land / …) so aapt2 derives the right resource type + configuration.
                val qualifier = editedLayout.parent?.fileName?.toString() ?: "layout"
                val overlaySrc = outDir.resolve("overlay-src")
                val overlayCompiled = outDir.resolve("overlay-compiled")
                overlaySrc.toFile().deleteRecursively()
                overlayCompiled.toFile().deleteRecursively()
                val overlayFile = overlaySrc.resolve(qualifier).resolve(editedLayout.fileName.toString())
                Files.createDirectories(overlayFile.parent)
                Files.write(overlayFile, rewritten.toByteArray())

                val overlay = aapt2.compile(listOf(overlaySrc), overlayCompiled)
                if (!overlay.result.success) {
                    locate(overlay.result.log, outDir, resDirs, overlaySrc to editedLayout).takeIf { it.isNotEmpty() }?.let { return errors(it) }
                    return Result(null, "aapt2 compile failed: ${overlay.result.log.takeLast(2).joinToString(" / ").ifBlank { "(no diagnostics)" }}")
                }

                progress("Linking resources")
                // base archives first, the live layout as an `-R` overlay → it overrides the saved copy (and
                // `--auto-add-overlay` lets a newly added layout in), without recompiling the whole project. NO
                // --extra-packages: a library's `R` is the app's under another name, resolved by the renderer.
                val linkRes = aapt2.link(
                    compiled = base,
                    manifest = manifest,
                    androidJar = frameworkTable(),
                    customPackage = packageName,
                    extraPackages = emptyList(),
                    minSdk = minSdk,
                    targetSdk = targetSdk,
                    genJavaDir = outDir.resolve("gen"),
                    outApk = previewAp,
                    overlays = overlay.archives,
                )
                if (!linkRes.success) {
                    locate(linkRes.log, outDir, resDirs, overlaySrc to editedLayout).takeIf { it.isNotEmpty() }?.let { return errors(it) }
                    return Result(null, "aapt2 link failed: ${linkRes.log.takeLast(3).joinToString(" / ").ifBlank { "(no diagnostics)" }}")
                }
                // The next edit of this layout can patch this output instead of relinking ([quickRelink]).
                Files.write(outDir.resolve(FULL_KEY), "$fullKey\n${Files.size(previewAp)}".toByteArray())
            }
            Files.write(keyFile, key.toByteArray())
        }
        val tLink = System.nanoTime()

        val previewRJar = ensurePreviewRJar(outDir, packageName, progress)
        fun ms(a: Long, b: Long) = (b - a) / 1_000_000
        log.info(
            "preview link: base=${ms(t0, tBase)}ms link=${ms(tBase, tLink)}ms${if (quick) " (quick)" else ""} " +
                "rJar=${ms(tLink, System.nanoTime())}ms"
        )
        val toolsJar = runCatching { PreviewToolsClasses.jar(cacheRoot) }
            .onFailure { log.warn("preview tools classes unavailable: ${it.message}") }.getOrNull()
        return Result(previewAp, rJar = previewRJar, toolsJar = toolsJar)
    }

    /**
     * Relinks only the edited layout, when that is all that changed: no `@+id` the table lacks, and [previewAp]
     * still descends from the last full link of this layout ([FULL_KEY] holds [fullKey]). The layout is linked
     * alone against [previewAp] as an include (aapt2 then resolves every reference to the table's own ids, in a
     * fraction of a full link, which rebuilds the whole table), and its compiled copies replace the old ones in
     * a new [previewAp] ([ApkEntryPatcher], which appends them and leaves the old ones as dead bytes, so a long
     * run of patches is capped by a full link). The ids do not change, so neither does the `R` jar. Returns
     * false, having left [previewAp] as it was, whenever the full link is needed instead (including when the
     * layout does not link: the full link reports the error against the user's file).
     */
    private fun quickRelink(
        outDir: Path, fullKey: String, manifest: Path, edited: Path, text: String, packageName: String,
        minSdk: Int, targetSdk: Int, previewAp: Path,
    ): Boolean {
        val record = readTextOrNull(outDir.resolve(FULL_KEY))?.lines() ?: return false
        if (!Files.isRegularFile(previewAp) || record.firstOrNull() != fullKey) return false
        val fullSize = record.getOrNull(1)?.toLongOrNull() ?: return false
        if (Files.size(previewAp) > fullSize + MAX_PATCH_GROWTH) return false
        val full = previewAp
        val ids = declaredIds(outDir, packageName) ?: return false
        // Ids the table already has are referenced, not declared: declaring one again in this scratch package
        // would give the layout's views ids the table does not know.
        var unknownId = false
        val referenced = Regex("""@\+id/([A-Za-z0-9_.]+)""").replace(text) { m ->
            if (m.groupValues[1].replace('.', '_') in ids) "@id/" + m.groupValues[1] else { unknownId = true; m.value }
        }
        if (unknownId) return false

        val qualifier = edited.parent?.fileName?.toString() ?: "layout"
        val name = edited.fileName.toString()
        val src = outDir.resolve("quick-src")
        val compiled = outDir.resolve("quick-compiled")
        src.toFile().deleteRecursively()
        compiled.toFile().deleteRecursively()
        Files.createDirectories(src.resolve(qualifier))
        Files.write(src.resolve(qualifier).resolve(name), referenced.toByteArray())
        val c = aapt2.compile(listOf(src), compiled)
        if (!c.result.success) return false
        val scratch = outDir.resolve("quick.ap_")
        val linked = aapt2.linkAgainst(c.archives, manifest, listOf(frameworkTable(), full), 0x80, minSdk, targetSdk, scratch)
        if (!linked.success) return false

        // The compiled layout (and any API-versioned copies aapt2 made of it) must be exactly the entries the
        // table already holds for it; anything else needs the table itself to change.
        val ownEntry = Regex("res/${Regex.escape(qualifier)}(-v\\d+)?/${Regex.escape(name)}")
        val produced = java.util.zip.ZipFile(scratch.toFile()).use { z ->
            z.entries().asSequence().filter { it.name.startsWith("res/") }
                .associate { it.name to z.getInputStream(it).use { s -> s.readBytes() } }
        }
        val existing = java.util.zip.ZipFile(full.toFile()).use { z ->
            z.entries().asSequence().map { it.name }.filter { ownEntry.matches(it) }.toSet()
        }
        if (produced.isEmpty() || produced.keys != existing) return false

        val tmp = outDir.resolve("resources.ap_.tmp")
        if (!ApkEntryPatcher.replace(full, tmp, produced)) return false
        Files.move(tmp, previewAp, StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    /** The id names the last full link's `R` declares (`R.id`), or null without one. */
    private fun declaredIds(outDir: Path, packageName: String): Set<String>? {
        val rJava = outDir.resolve("gen").resolve(packageName.replace('.', '/')).resolve("R.java")
        val text = readTextOrNull(rJava) ?: return null
        val start = text.indexOf("class id {").takeIf { it >= 0 } ?: return emptySet()
        val end = text.indexOf('}', start)
        return Regex("""int\s+(\w+)\s*=""").findAll(text.substring(start, end)).map { it.groupValues[1] }.toSet()
    }

    /** What a full link's output depends on besides the edited layout's text: [quickRelink] patches it only
     *  while these are unchanged. */
    private fun fullLinkKey(
        base: List<Path>, manifest: Path, edited: Path, packageName: String, minSdk: Int, targetSdk: Int,
    ): String = digest {
        put("full v1 rewriter ${PreviewLayoutRewriter.VERSION}")
        for (a in (base + manifest).sortedBy { it.toString() }) putFileSig(a)
        put(edited.toString()); put(packageName); put("$minSdk/$targetSdk")
    }

    /**
     * The preview R.jar: the app's `R`, built from the `R.java` the preview link just wrote, so its ids always
     * match the rendered resource table (including an `@+id` that exists only in the unsaved buffer). Keyed on
     * that file's CONTENT: a save or an edit that leaves the ids alone reuses the jar as it is. Returns the
     * current jar, a stale one if regeneration fails, or null.
     */
    private fun ensurePreviewRJar(outDir: Path, packageName: String, progress: (String) -> Unit): Path? {
        val previewRJar = outDir.resolve(R_JAR_NAME)
        val rJava = outDir.resolve("gen").resolve(packageName.replace('.', '/')).resolve("R.java")
        if (!Files.isRegularFile(rJava)) return previewRJar.takeIf { Files.exists(it) }
        val rKeyFile = outDir.resolve("r-key.txt")
        val rKey = digest {
            put("v3")
            update(Files.readAllBytes(rJava))
        }
        if (Files.exists(previewRJar) && readTextOrNull(rKeyFile) == rKey) return previewRJar

        val regenerated = runCatching {
            progress("Generating R")
            val tmp = outDir.resolve("preview-r.jar.tmp")
            RBytecodeGenerator.writeUncompressedJar(rJava, tmp)
            Files.move(tmp, previewRJar, StandardCopyOption.REPLACE_EXISTING)
            Files.write(rKeyFile, rKey.toByteArray())
            previewRJar
        }.getOrNull()
        // On a regeneration failure keep any previously-generated R.jar (stale but usable) over nothing.
        return regenerated ?: previewRJar.takeIf { Files.exists(it) }
    }

    /**
     * The framework resource table the per-edit link includes (`-I`): [androidJar]'s `resources.arsc` and
     * manifest copied once into an UNCOMPRESSED jar. In the SDK's jar the 18 MB table is deflated, so every link
     * inflated it before doing anything else, about a fifth of the link. Falls back to [androidJar] itself.
     */
    private fun frameworkTable(): Path = runCatching {
        val key = digest { putFileSig(androidJar) }.take(12)
        val stored = cacheRoot.resolve("framework-res-$key.jar")
        if (Files.isRegularFile(stored)) return@runCatching stored
        Files.createDirectories(cacheRoot)
        val tmp = cacheRoot.resolve("framework-res-$key.jar.tmp")
        java.util.zip.ZipFile(androidJar.toFile()).use { jar ->
            java.util.zip.ZipOutputStream(Files.newOutputStream(tmp).buffered(1 shl 16)).use { out ->
                for (name in listOf("AndroidManifest.xml", "resources.arsc")) {
                    val entry = jar.getEntry(name) ?: continue
                    val bytes = jar.getInputStream(entry).use { it.readBytes() }
                    out.putNextEntry(java.util.zip.ZipEntry(name).apply {
                        method = java.util.zip.ZipEntry.STORED
                        size = bytes.size.toLong(); compressedSize = size
                        crc = java.util.zip.CRC32().apply { update(bytes) }.value
                    })
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        Files.move(tmp, stored, StandardCopyOption.REPLACE_EXISTING)
        stored
    }.getOrElse { androidJar }

    /** The outcome of [selfBuildBase]: the compiled archives, or the resource errors the compile stopped on. */
    private sealed interface Base {
        class Built(val archives: List<Path>) : Base
        class Broken(val errors: List<ResourceError>) : Base
    }

    /**
     * Merge + compile the live [resDirs] into a base archive, reused while the files on disk are unchanged,
     * apart from [edited]. The edited layout is always linked from the live buffer as an overlay, so its disk
     * copy in the base is never what renders: saving it does not invalidate the base (a full merge + compile).
     * It counts again once another layout is previewed, so a saved change there is merged in then. Returns the
     * compiled archive(s), the errors aapt2 reported in the project's resources, or null when the
     * merge/compile failed without naming one (→ caller falls back to the build's flats).
     */
    private fun selfBuildBase(outDir: Path, resDirs: List<Path>, edited: Path, progress: (String) -> Unit): Base? {
        val baseDir = outDir.resolve("base")
        val compiledDir = baseDir.resolve("compiled")
        val keyFile = baseDir.resolve("res-key.txt")
        val sigs = fileSigs(resDirs)
        val fingerprint = fingerprintOf(sigs)
        val cached = listZips(compiledDir)
        if (cached.isNotEmpty() && sameExcept(readTextOrNull(keyFile), sigs, normalized(edited))) return Base.Built(cached)
        baseErrors[outDir]?.let { (fp, errors) -> if (fp == fingerprint) return Base.Broken(errors) }
        progress("Merging resources")

        return runCatching {
            val merged = baseDir.resolve("merged")
            val tm = System.nanoTime()
            mergeMaps[outDir] = fingerprint to mergeResourceDirs(resDirs.filter { Files.isDirectory(it) }, merged)
            rewriteLayouts(merged)
            compiledDir.toFile().deleteRecursively()
            val tc = System.nanoTime()
            val r = aapt2.compile(listOf(merged), compiledDir)
            log.info("preview base: merge=${(tc - tm) / 1_000_000}ms compile=${(System.nanoTime() - tc) / 1_000_000}ms")
            if (!r.result.success) {
                val errors = locate(r.result.log, outDir, resDirs, overlay = null)
                if (errors.isEmpty()) return@runCatching null
                baseErrors[outDir] = fingerprint to errors
                return@runCatching Base.Broken(errors)
            }
            baseErrors.remove(outDir)
            val zips = listZips(compiledDir)
            if (zips.isEmpty()) return@runCatching null
            Files.write(keyFile, sigs.entries.joinToString("\n") { "${it.key}\t${it.value}" }.toByteArray())
            Base.Built(zips)
        }.getOrNull()
    }

    /** Rewrites every merged layout for the preview ([PreviewLayoutRewriter]): an included layout or a list item
     *  shows its design-time values too, and a data-binding layout compiles. The merge is the preview's own copy. */
    private fun rewriteLayouts(merged: Path) {
        if (!Files.isDirectory(merged)) return
        // collect(), not Stream.toList(): the member is JDK 16, which Android only has from API 34.
        Files.list(merged).use { dirs -> dirs.filter { it.fileName.toString().startsWith("layout") }.collect(Collectors.toList()) }.forEach { dir ->
            Files.list(dir).use { files -> files.filter { it.toString().endsWith(".xml") }.collect(Collectors.toList()) }.forEach { f ->
                runCatching {
                    val text = Files.readAllBytes(f).decodeToString()
                    val rewritten = PreviewLayoutRewriter.rewrite(text, frameworkAttrs)
                    if (rewritten != text) Files.write(f, rewritten.toByteArray())
                }
            }
        }
    }

    /** The framework's attribute names (`android.R.attr`), the set a `tools:` attribute may stand in for. */
    private val frameworkAttrs: Set<String> by lazy {
        runCatching {
            java.util.zip.ZipFile(androidJar.toFile()).use { jar ->
                val entry = jar.getEntry("android/R\$attr.class") ?: return@use emptySet<String>()
                val names = HashSet<String>()
                org.objectweb.asm.ClassReader(jar.getInputStream(entry).use { it.readBytes() }).accept(
                    object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        override fun visitField(access: Int, name: String, d: String?, s: String?, v: Any?) =
                            null.also { names += name }
                    },
                    org.objectweb.asm.ClassReader.SKIP_CODE,
                )
                names
            }
        }.getOrElse { log.warn("framework attributes unreadable: ${it.message}"); emptySet() }
    }

    /** Whether the base recorded as [stored] (one `path<TAB>sig` line per file) matches [sigs] in every file
     *  except [edited]. An unreadable or old-format record never matches, so the base rebuilds once. */
    private fun sameExcept(stored: String?, sigs: Map<String, String>, edited: String): Boolean {
        if (stored.isNullOrEmpty()) return false
        val was = HashMap<String, String>()
        for (line in stored.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) return false
            was[line.substring(0, tab)] = line.substring(tab + 1)
        }
        was.remove(edited)
        return sigs.count { it.key != edited } == was.size && sigs.all { (path, sig) -> path == edited || was[path] == sig }
    }

    /** A failed [Result] carrying [errors], summarized by the first one for callers that only read [Result.error]. */
    private fun errors(errors: List<ResourceError>): Result {
        val first = errors.first()
        val where = first.file?.let { f -> f.fileName.toString() + (first.line?.let { ":$it" } ?: "") + ": " } ?: ""
        return Result(null, "resource error: $where${first.message}", resourceErrors = errors)
    }

    /**
     * The aapt2 errors in [log], each moved from the preview's own copy to the user's file: the live [overlay]
     * tree (its dir to the edited layout) maps to that layout, and the merged base maps through the
     * [ResourceMergeMap] of the merge that produced it. An error naming any other path keeps it.
     */
    private fun locate(log: List<String>, outDir: Path, resDirs: List<Path>, overlay: Pair<Path, Path>?): List<ResourceError> {
        val merged = outDir.resolve("base").resolve("merged")
        return Aapt2Errors.parse(log).map { e ->
            val path = e.path?.let { runCatching { Paths.get(it).toAbsolutePath().normalize() }.getOrNull() }
                ?: return@map ResourceError(null, e.line, e.message)
            when {
                overlay != null && path.startsWith(overlay.first.toAbsolutePath().normalize()) ->
                    ResourceError(overlay.second, e.line, e.message)
                path.startsWith(merged.toAbsolutePath().normalize()) -> {
                    val (file, line) = mergeMap(outDir, resDirs, merged)?.sourceOf(path, e.line) ?: (path to e.line)
                    ResourceError(file, line, e.message)
                }
                else -> ResourceError(path, e.line, e.message)
            }
        }
    }

    /** The merge map of the current base. After a restart the base is reused from disk without merging, so the
     *  map is rebuilt by merging again (the same inputs give the same output), which only an error path needs. */
    private fun mergeMap(outDir: Path, resDirs: List<Path>, merged: Path): ResourceMergeMap? {
        val fingerprint = fingerprint(resDirs)
        mergeMaps[outDir]?.let { (fp, map) -> if (fp == fingerprint) return map }
        return runCatching { mergeResourceDirs(resDirs.filter { Files.isDirectory(it) }, merged) }.getOrNull()
            ?.also { mergeMaps[outDir] = fingerprint to it }
    }

    /**
     * The manifest every preview link runs against: just the `package` (the module namespace, which the render
     * and the generated `R` both key on) and an empty `<application/>`. Rewritten only when its content changes,
     * since its mtime feeds the link and R.jar cache keys.
     */
    private fun previewManifest(outDir: Path, packageName: String): Path {
        val manifest = outDir.resolve("AndroidManifest.xml")
        val text = """<?xml version="1.0" encoding="utf-8"?>
            |<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName">
            |    <application/>
            |</manifest>
            |""".trimMargin()
        if (readTextOrNull(manifest) != text) Files.write(manifest, text.toByteArray())
        return manifest
    }

    /** The build's compiled resource flats, if a prior build produced them — a stale-but-valid fallback base. */
    private fun fallbackBase(module: Module, variant: String): List<Path>? =
        listZips(AndroidBuildSystem.compiledResPath(module, variant)).takeIf { it.isNotEmpty() }

    private fun listZips(dir: Path): List<Path> =
        if (Files.isDirectory(dir)) Files.list(dir).use { s -> s.filter { it.toString().endsWith(".zip") }.sorted().collect(Collectors.toList()) }
        else emptyList()

    /** Every live res file's signature (`mtime:size`), keyed by its normalized path, in path order. */
    private fun fileSigs(resDirs: List<Path>): Map<String, String> {
        val out = java.util.TreeMap<String, String>()
        resDirs.filter { Files.isDirectory(it) }.forEach { dir ->
            Files.walk(dir).use { s ->
                s.filter { Files.isRegularFile(it) }.forEach { f ->
                    val mtime = runCatching { Files.getLastModifiedTime(f).toMillis() }.getOrDefault(0L)
                    val size = runCatching { Files.size(f) }.getOrDefault(0L)
                    out[normalized(f)] = "$mtime:$size"
                }
            }
        }
        // Not a file: the base's merged layouts are rewritten, so a new rewriter rebuilds it.
        out["#layout-rewriter"] = PreviewLayoutRewriter.VERSION
        return out
    }

    /** SHA-1 over [sigs]: stable while the on-disk tree is unchanged. */
    private fun fingerprintOf(sigs: Map<String, String>): String = digest {
        sigs.forEach { (path, sig) -> put(path); put(sig) }
    }

    /** SHA-1 over the live res files (path + mtime + size). */
    private fun fingerprint(resDirs: List<Path>): String = fingerprintOf(fileSigs(resDirs))

    private fun normalized(p: Path): String = p.toAbsolutePath().normalize().toString()

    /** Keyed on the base archives + manifest + the live buffer, so the ARSC relink runs only when one changed. */
    private fun cacheKey(base: List<Path>, manifest: Path, editedLayout: Path, liveText: String): String =
        digest {
            put("rewriter " + PreviewLayoutRewriter.VERSION)
            for (a in (base + manifest).sortedBy { it.toString() }) putFileSig(a)
            put(editedLayout.toString())
            update(liveText.toByteArray())
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
