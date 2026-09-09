package dev.ide.core

import dev.ide.model.impl.format.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors
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

    /** Choices from the export screen. */
    data class ExportOptions(
        val bundleDependencies: Boolean,
        val author: String,
        val description: String,
        /**
         * The modules to package, by name. `null` (the default) packages every module; a subset drops the
         * other modules' directories and rewrites the packaged `workspace.json` so the import opens with
         * exactly these modules. A module still listed as a dependency of a packaged one would leave the
         * import unbuildable, so the caller is expected to keep the selection dependency-closed.
         */
        val includedModules: Set<String>? = null,
        /** Image files to embed as preview screenshots, in display order; unreadable ones are skipped. */
        val screenshotPaths: List<String> = emptyList(),
    )

    /** One module of the project being exported: its [name], its directory [path] relative to the project
     *  root (`""` at the root), and its [typeId] (`android-app`, `java-lib`, ...). */
    data class ModuleSpec(val name: String, val path: String, val typeId: String)

    /** Project-derived metadata the exporter can't compute from the file tree alone. */
    data class ExportMeta(
        val name: String,
        val isAndroid: Boolean,
        val packageName: String?,
        val modules: List<ModuleSpec>,
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
        /** The packaged modules with their file counts, from the manifest or (for a package written before
         *  the manifest carried them) reconstructed from the entry paths. */
        val modules: List<CaprojModuleInfo>,
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
    private const val WORKSPACE_REL = ".platform/workspace.json"
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
        val packed = meta.modules.filter { options.includedModules?.contains(it.name) ?: true }
        val dropped = meta.modules - packed.toSet()
        val files = collectProjectFiles(projectDir, dropped.map { it.path }.filter { it.isNotEmpty() })
        val moduleInfos = moduleStats(projectDir, files, packed)
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
            moduleCount = packed.size,
            modules = packed.map { it.name },
            moduleInfos = moduleInfos,
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
                val name = CaprojFormat.PROJECT_PREFIX + rel
                // A partial export ships a model listing only the packaged modules, so the import doesn't open
                // against modules whose directories aren't in the package.
                val pruned = if (rel == WORKSPACE_REL && dropped.isNotEmpty()) prunedWorkspace(file, packed.map { it.name }.toSet()) else null
                if (pruned != null) putBytes(zip, name, pruned) else putFile(zip, name, file)
            }
            store?.screenshots?.forEachIndexed { i, bytes -> putBytes(zip, screenshotEntries[i], bytes) }
            if (options.bundleDependencies) writeBundledDeps(zip, projectDir)
        }
        return out
    }

    /**
     * What each of [modules] would contribute to a package of [projectDir], measured with the same walk and
     * exclusions [export] uses — so the export screen's per-module numbers are the ones the package ends up
     * carrying. Costs one file-tree walk.
     */
    fun measure(projectDir: Path, modules: List<ModuleSpec>): List<CaprojModuleInfo> =
        moduleStats(projectDir, collectProjectFiles(projectDir), modules)

    /** Total bytes bundling the resolved dependencies would add to a package (what "bundle dependencies"
     *  costs), or 0 when nothing has been resolved yet. */
    fun bundledDepsSize(projectDir: Path): Long {
        var total = runCatching { Files.size(projectDir.resolve(LIBRARIES_REL)) }.getOrDefault(0L)
        val cache = projectDir.resolve(RESOLVED_DEPS_REL)
        if (!Files.isDirectory(cache)) return total
        Files.walk(cache).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { total += runCatching { Files.size(it) }.getOrDefault(0L) }
        }
        return total
    }

    /** Rename the project inside an unpacked workspace's `workspace.json` — the display name the picker and
     *  the editor show. Best-effort: a workspace file it can't parse is left alone. */
    fun renameProject(projectDir: Path, name: String) {
        if (name.isBlank()) return
        val workspace = projectDir.resolve(WORKSPACE_REL)
        val renamed = runCatching {
            val root = Json.parse(Files.readAllBytes(workspace).toString(Charsets.UTF_8)) as? Map<*, *> ?: return
            val projects = (root["projects"] as? List<*>)?.map { project ->
                val map = project as? Map<*, *> ?: return@map project
                LinkedHashMap(map).apply { this["name"] = name }
            } ?: return
            Json.write(LinkedHashMap(root).apply { this["projects"] = projects })
        }.getOrNull() ?: return
        runCatching { Files.write(workspace, renamed.toByteArray(Charsets.UTF_8)) }
    }

    /** The regular files under [projectDir] that belong in a package (source-of-truth only), minus anything
     *  under [droppedDirs] (the directories of modules the user left out of the export). */
    private fun collectProjectFiles(projectDir: Path, droppedDirs: List<String> = emptyList()): List<Path> =
        Files.walk(projectDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { file ->
                    val rel = projectDir.relativize(file).toString().replace(File.separatorChar, '/')
                    !isExcluded(rel, file.fileName.toString()) && !isUnder(rel, droppedDirs)
                }
                .sorted()
                // Not Stream.toList(): that is a JDK 16 method, missing on the older ART runtimes this ships to.
                .collect(Collectors.toList())
        }

    /** True when [rel] names a file inside one of [dirs] (each a project-relative directory path). */
    private fun isUnder(rel: String, dirs: List<String>): Boolean = dirs.any { rel.startsWith("$it/") }

    /**
     * Split the packaged [files] across the [modules] that own them: each file counts towards the module with
     * the longest directory path containing it, so a nested module isn't also counted by its parent. Files
     * outside every module (the root's `.platform/`, top-level scripts) are counted by no module, which is
     * why the per-module totals can add up to less than the manifest's file count.
     */
    private fun moduleStats(projectDir: Path, files: List<Path>, modules: List<ModuleSpec>): List<CaprojModuleInfo> {
        if (modules.isEmpty()) return emptyList()
        val fileCounts = IntArray(modules.size)
        val sizes = LongArray(modules.size)
        // Longest path first, so `app/feature` wins over `app` for a file inside it.
        val order = modules.indices.sortedByDescending { modules[it].path.length }
        for (file in files) {
            val rel = projectDir.relativize(file).toString().replace(File.separatorChar, '/')
            val owner = order.firstOrNull { i ->
                val path = modules[i].path
                path.isEmpty() || rel.startsWith("$path/")
            } ?: continue
            fileCounts[owner]++
            sizes[owner] += runCatching { Files.size(file) }.getOrDefault(0L)
        }
        return modules.mapIndexed { i, m -> CaprojModuleInfo(m.name, m.path, m.typeId, fileCounts[i], sizes[i]) }
    }

    /** [workspace] re-serialized with every module not in [keep] removed, or null when it can't be parsed
     *  (the caller then packages the file verbatim rather than shipping nothing). */
    private fun prunedWorkspace(workspace: Path, keep: Set<String>): ByteArray? = runCatching {
        val root = Json.parse(Files.readAllBytes(workspace).toString(Charsets.UTF_8)) as? Map<*, *> ?: return null
        val projects = (root["projects"] as? List<*>)?.map { project ->
            val map = project as? Map<*, *> ?: return@map project
            val modules = (map["modules"] as? List<*>)?.filter { (it as? Map<*, *>)?.get("name") in keep }
                ?: return@map project
            LinkedHashMap(map).apply { this["modules"] = modules }
        } ?: return null
        Json.write(LinkedHashMap(root).apply { this["projects"] = projects }).toByteArray(Charsets.UTF_8)
    }.getOrNull()

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
                val all = zf.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(CaprojFormat.PROJECT_PREFIX) }
                    .map { Entry(it.name.removePrefix(CaprojFormat.PROJECT_PREFIX), it.size.coerceAtLeast(0L)) }
                    .sortedBy { it.path }
                    .toList()
                val entries = all.take(entryLimit)
                val modules = manifest.moduleInfos.ifEmpty { modulesFromEntries(manifest.modules, all) }
                val iconBytes = manifest.iconEntry
                    ?.let { zf.getEntry(it) }
                    ?.let { entry -> zf.getInputStream(entry).use { it.readBytes() } }
                val screenshots = manifest.store?.screenshotEntries.orEmpty().take(MAX_SCREENSHOTS).mapNotNull { name ->
                    zf.getEntry(name)?.let { entry -> zf.getInputStream(entry).use { it.readBytes() } }
                }
                Preview(manifest, entries, modules, iconBytes, manifest.store, screenshots)
            }
        }.getOrNull()
    }

    /** Per-module counts for a package written before the manifest carried them: a module's name doubles as
     *  its directory, which is how every project this build creates is laid out. */
    private fun modulesFromEntries(names: List<String>, entries: List<Entry>): List<CaprojModuleInfo> =
        names.map { name ->
            val owned = entries.filter { it.path.startsWith("$name/") }
            CaprojModuleInfo(name, name, typeId = "", fileCount = owned.size, sizeBytes = owned.sumOf { it.size })
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
