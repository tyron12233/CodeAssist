package dev.ide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Reads and writes `.caproj` packages (see [CaprojFormat]). Export walks a project's tree, drops the
 * regenerable/host-specific files (the same set [ProjectManager] excludes from a backup), and writes the
 * survivors under `project/` alongside a [CaprojManifest] and an optional icon; import reverses it. All
 * extraction is zip-slip guarded. Bundled dependencies (opt-in at export) travel under `deps/` and are
 * restored into `.platform/` so an imported project builds offline.
 */
object ProjectPackaging {

    /** Choices from the export dialog. */
    data class ExportOptions(
        val bundleDependencies: Boolean,
        val author: String,
        val description: String,
    )

    /** Project-derived metadata the exporter can't compute from the file tree alone. */
    data class ExportMeta(
        val name: String,
        val isAndroid: Boolean,
        val packageName: String?,
        val modules: List<String>,
        val createdBy: String,
        val exportedAt: Long,
    )

    /** Optional Explore/Store content bundled at publish time (screenshots + catalog metadata). */
    data class StoreContent(
        val summary: String,
        val category: String,
        val tags: List<String>,
        val highlights: List<String>,
        val language: String?,
        /** Screenshot image bytes (PNG), in display order. */
        val screenshots: List<ByteArray>,
    )

    /** One file listed in the import preview (path relative to the project root). */
    data class Entry(val path: String, val size: Long)

    /** Everything the import preview needs, read without extracting the package. */
    data class Preview(
        val manifest: CaprojManifest,
        val entries: List<Entry>,
        val iconBytes: ByteArray?,
        val storeInfo: CaprojStoreInfo?,
        /** Decoded screenshot bytes (Explore metadata), in display order. */
        val screenshots: List<ByteArray>,
    )

    /** Cap on screenshots read for a preview (a shared project ships a handful). */
    private const val MAX_SCREENSHOTS = 10

    // Bundled-deps layout inside the package: `.platform/libraries.json` and the resolved-artifact cache.
    private const val DEPS_LIBRARIES = "libraries.json"
    private const val DEPS_RESOLVED = "resolved-deps/"
    private const val LIBRARIES_REL = ".platform/libraries.json"
    private const val RESOLVED_DEPS_REL = ".platform/caches/resolved-deps"

    // --- export ---

    /** Write [projectDir] to [out] as a `.caproj`, embedding [iconBytes] as the preview icon when non-null and
     *  the optional Explore [store] content (screenshots + catalog metadata) under `store/`. */
    fun export(
        projectDir: Path,
        out: Path,
        options: ExportOptions,
        iconBytes: ByteArray?,
        meta: ExportMeta,
        store: StoreContent? = null,
    ): Path {
        val files = collectProjectFiles(projectDir)
        val screenshotEntries = store?.screenshots?.indices?.map { "${CaprojFormat.STORE_PREFIX}screenshots/$it.png" } ?: emptyList()
        val storeInfo = store?.let {
            CaprojStoreInfo(it.summary, it.category, it.tags, it.highlights, it.language, screenshotEntries)
        }
        val manifest = CaprojManifest(
            format = CaprojFormat.FORMAT_VERSION,
            kind = CaprojFormat.KIND_PROJECT,
            name = meta.name,
            description = options.description.trim(),
            author = options.author.trim(),
            createdBy = meta.createdBy,
            exportedAt = meta.exportedAt,
            isAndroid = meta.isAndroid,
            packageName = meta.packageName,
            moduleCount = meta.modules.size,
            modules = meta.modules,
            fileCount = files.size,
            uncompressedSize = files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) },
            hasBundledDeps = options.bundleDependencies,
            iconEntry = if (iconBytes != null) CaprojFormat.ICON_ENTRY else null,
            store = storeInfo,
        )
        out.parent?.let { Files.createDirectories(it) }
        ZipOutputStream(Files.newOutputStream(out)).use { zip ->
            putBytes(zip, CaprojFormat.MANIFEST_ENTRY, CaprojFormat.encode(manifest).toByteArray(Charsets.UTF_8))
            if (iconBytes != null) putBytes(zip, CaprojFormat.ICON_ENTRY, iconBytes)
            for (file in files) {
                val rel = projectDir.relativize(file).toString().replace(File.separatorChar, '/')
                putFile(zip, CaprojFormat.PROJECT_PREFIX + rel, file)
            }
            store?.screenshots?.forEachIndexed { i, bytes -> putBytes(zip, screenshotEntries[i], bytes) }
            if (options.bundleDependencies) writeBundledDeps(zip, projectDir)
        }
        return out
    }

    /** The regular files under [projectDir] that belong in a package (source-of-truth only). */
    private fun collectProjectFiles(projectDir: Path): List<Path> =
        Files.walk(projectDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { !isExcluded(projectDir.relativize(it).toString().replace(File.separatorChar, '/'), it.fileName.toString()) }
                .sorted()
                .toList()
        }

    /** Bulky/derived/host-specific files kept out of a package. Mirrors [ProjectManager]'s backup exclusions,
     *  plus the resolved-dependency cache (bundled separately under `deps/` only when the user opts in) and
     *  the absolute-path `sdks.json` / per-project bundled stdlib (both reseeded on open). */
    private fun isExcluded(rel: String, fileName: String): Boolean {
        if (fileName == "android.jar" || fileName == "debug.keystore") return true
        if (rel == LIBRARIES_REL || rel == ".platform/sdks.json") return true
        if (rel == ".platform/.deps-reconciled" || rel == ".platform/.deps-unresolved") return true
        if (rel.startsWith(".platform/kotlin-stdlib-") && rel.endsWith(".jar")) return true
        if (rel.contains(".platform/caches/")) return true
        return rel.split('/').any { it == "build" || it == "exports" || it == ".gradle" }
    }

    /** Copy the resolved dependency cache + `libraries.json` under `deps/` so the import builds offline. */
    private fun writeBundledDeps(zip: ZipOutputStream, projectDir: Path) {
        val libraries = projectDir.resolve(LIBRARIES_REL)
        if (Files.isRegularFile(libraries)) putFile(zip, CaprojFormat.DEPS_PREFIX + DEPS_LIBRARIES, libraries)
        val cache = projectDir.resolve(RESOLVED_DEPS_REL)
        if (!Files.isDirectory(cache)) return
        Files.walk(cache).use { stream ->
            stream.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                val rel = cache.relativize(file).toString().replace(File.separatorChar, '/')
                putFile(zip, CaprojFormat.DEPS_PREFIX + DEPS_RESOLVED + rel, file)
            }
        }
    }

    // --- read (preview) ---

    /** Read the manifest + up to [entryLimit] file entries + the icon from [archive], without extracting.
     *  Returns null when [archive] isn't a readable package with a valid manifest. */
    fun readPreview(archive: Path, entryLimit: Int = 400): Preview? {
        return runCatching {
            ZipFile(archive.toFile()).use { zf ->
                val manifestEntry = zf.getEntry(CaprojFormat.MANIFEST_ENTRY) ?: return null
                val manifest = zf.getInputStream(manifestEntry).use { CaprojFormat.decode(it.readBytes().toString(Charsets.UTF_8)) } ?: return null
                val entries = zf.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(CaprojFormat.PROJECT_PREFIX) }
                    .map { Entry(it.name.removePrefix(CaprojFormat.PROJECT_PREFIX), it.size.coerceAtLeast(0L)) }
                    .sortedBy { it.path }
                    .take(entryLimit)
                    .toList()
                val iconBytes = manifest.iconEntry
                    ?.let { zf.getEntry(it) }
                    ?.let { entry -> zf.getInputStream(entry).use { it.readBytes() } }
                val screenshots = manifest.store?.screenshotEntries.orEmpty().take(MAX_SCREENSHOTS).mapNotNull { name ->
                    zf.getEntry(name)?.let { entry -> zf.getInputStream(entry).use { it.readBytes() } }
                }
                Preview(manifest, entries, iconBytes, manifest.store, screenshots)
            }
        }.getOrNull()
    }

    // --- import (extract) ---

    /** Extract [archive]'s `project/` tree into [destProjectDir], restoring any bundled `deps/` into
     *  `.platform/`. Zip-slip guarded. Throws on a malformed archive. */
    fun unpack(archive: Path, destProjectDir: Path) {
        val dest = destProjectDir.normalize()
        Files.createDirectories(dest)
        ZipFile(archive.toFile()).use { zf ->
            for (entry in zf.entries()) {
                val target = when {
                    entry.name.startsWith(CaprojFormat.PROJECT_PREFIX) ->
                        dest.resolve(entry.name.removePrefix(CaprojFormat.PROJECT_PREFIX))
                    entry.name.startsWith(CaprojFormat.DEPS_PREFIX) ->
                        dest.resolve(depsTargetRel(entry.name.removePrefix(CaprojFormat.DEPS_PREFIX)))
                    else -> continue // manifest.json / icon.png are metadata, not extracted
                }.normalize()
                require(target.startsWith(dest)) { "Package entry escapes target: ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    zf.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
        }
    }

    /** Map a `deps/` entry back to its project-relative location under `.platform/`. */
    private fun depsTargetRel(rel: String): String = when {
        rel == DEPS_LIBRARIES -> LIBRARIES_REL
        rel.startsWith(DEPS_RESOLVED) -> "$RESOLVED_DEPS_REL/${rel.removePrefix(DEPS_RESOLVED)}"
        else -> ".platform/$rel"
    }

    // --- zip helpers ---

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, name: String, file: Path) {
        zip.putNextEntry(ZipEntry(name))
        Files.copy(file, zip)
        zip.closeEntry()
    }
}
