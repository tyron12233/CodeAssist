// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform.storage

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Computes on-disk storage usage broken down by category and clears the regenerable ones. Pure
 * `java.nio.file` with no IDE dependencies, so it lives in `platform-core` and is unit-tested under
 * `CI_CORE_ONLY`; the backend adapts [Report] to the UI DTOs and supplies the roots.
 *
 * Two roots. [storageRoot] is the app's whole managed folder — what a file manager browses.
 * [sharedRoot] (the app home dir) holds the cross-project `caches/` and `.platform/` toolchain dirs;
 * on desktop the two paths are equal, and the split only matters on device (where [storageRoot] can
 * sit above [sharedRoot] and hold sibling data such as a previous app version's projects).
 *
 * **Safety.** [clearCategory] deletes ONLY the directories a *clearable* category maps to. The
 * [PROJECTS] and [OTHER] categories (project source/config, keystores, exports) map to no
 * clear-targets, so clearing can never remove them. Only the app-owned toolchain dirs under
 * `.platform` are targeted for [SDK] — never a system Android SDK that lives outside [sharedRoot].
 */
object StorageUsage {

    // Stable category ids — the backend/UI key colors, titles, and clear routing off these.
    const val DEPENDENCIES = "dependencies"
    const val INDEX = "index"
    const val BUILD = "build"
    const val PREVIEW = "preview"
    const val LANGUAGE = "language"
    const val SDK = "sdk"
    const val PROJECTS = "projects"
    const val OTHER = "other"

    /** Fixed display order of categories in a [Report]. */
    val ORDER = listOf(DEPENDENCIES, INDEX, BUILD, PREVIEW, LANGUAGE, SDK, PROJECTS, OTHER)

    /** Categories a [clearCategory] call will act on; the rest are read-only. */
    val CLEARABLE = setOf(DEPENDENCIES, INDEX, BUILD, PREVIEW, LANGUAGE, SDK)

    /** Categories whose clear is destructive enough to warrant a confirmation before it runs. */
    val DESTRUCTIVE = setOf(SDK)

    data class Report(
        val storageRoot: String,
        val totalBytes: Long,
        val categories: List<Cat>,
        val projects: List<Proj>,
    )

    data class Cat(val id: String, val bytes: Long)

    /**
     * A managed project. [bytes] is the whole on-disk size freed by deleting it, INCLUDING its caches
     * (which are also counted under the cache categories), so a deletion frees at least this much.
     */
    data class Proj(val name: String, val rootPath: String, val bytes: Long)

    /** Per-project `.platform/caches/<name>` subdir → category. Unmapped cache dirs fall through to the
     *  project's source bucket (conservative — never lost, never mis-cleared). */
    private val PROJECT_CACHE_CATEGORY: Map<String, String> = mapOf(
        "aar-res" to DEPENDENCIES,
        "source-index" to INDEX,
        "build" to BUILD,
        "preview" to PREVIEW,
        "preview-libs" to PREVIEW,
        "kotlin-ext" to LANGUAGE,
        "custom-views" to LANGUAGE,
        "core-platform" to LANGUAGE,
    )

    /** Shared (cross-project) directories/files a category owns under [sharedRoot]. */
    private fun sharedTargets(id: String, sharedRoot: Path): List<Path> = when (id) {
        DEPENDENCIES -> listOf(
            sharedRoot.resolve(".platform/caches/resolved-deps"),
            sharedRoot.resolve(".platform/caches/resolved-deps-misses"),
        )
        INDEX -> listOf(sharedRoot.resolve("caches/index"))
        BUILD -> listOf(sharedRoot.resolve("caches/dex"))
        PREVIEW -> listOf(sharedRoot.resolve("caches/preview-res"))
        SDK -> listOf(
            sharedRoot.resolve(".platform/sdk-downloads"),
            sharedRoot.resolve(".platform/android-sdk"),
            sharedRoot.resolve(".platform/jdk-src.zip"),
        )
        else -> emptyList()
    }

    /** Ids that have at least one shared target (used to loop shared sizing/clearing). */
    private val SHARED_CATEGORIES = listOf(DEPENDENCIES, INDEX, BUILD, PREVIEW, SDK)

    /**
     * Walk the managed storage once and return per-category sizes plus per-project totals. Each project
     * tree is walked exactly once (files bucketed into a cache category or the project's source), each
     * shared cache/SDK dir once, and everything else under [storageRoot] once (pruning the already-counted
     * subtrees), so nothing is walked or counted twice. [totalBytes] is the sum of the categories.
     */
    fun report(storageRoot: Path, projectRoots: List<Path>, sharedRoot: Path): Report {
        val bytes = LinkedHashMap<String, Long>().apply { ORDER.forEach { put(it, 0L) } }
        val projects = ArrayList<Proj>()

        // 1. Per-project single walk: cache files → their category, everything else → project source.
        for (proj in projectRoots) {
            if (!Files.isDirectory(proj)) continue
            val cacheRoot = proj.resolve(".platform").resolve("caches")
            var projTotal = 0L
            runCatching {
                Files.walk(proj).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        val size = sizeOf(file)
                        projTotal += size
                        val cat = cacheCategoryOf(cacheRoot, file) ?: PROJECTS
                        bytes.merge(cat, size, Long::plus)
                    }
                }
            }
            projects.add(Proj(proj.fileName?.toString() ?: proj.toString(), proj.toString(), projTotal))
        }

        // 2. Shared cache + SDK dirs.
        for (id in SHARED_CATEGORIES) {
            for (target in sharedTargets(id, sharedRoot)) bytes.merge(id, treeSize(target), Long::plus)
        }

        // 3. Everything else under storageRoot → "other", pruning the subtrees already counted above.
        val counted = buildSet {
            projectRoots.forEach { add(it.normalize()) }
            SHARED_CATEGORIES.forEach { id -> sharedTargets(id, sharedRoot).forEach { add(it.normalize()) } }
        }
        bytes.merge(OTHER, otherSize(storageRoot, counted), Long::plus)

        val categories = ORDER.map { Cat(it, bytes[it] ?: 0L) }
        return Report(
            storageRoot = storageRoot.toString(),
            totalBytes = categories.sumOf { it.bytes },
            categories = categories,
            projects = projects.sortedByDescending { it.bytes },
        )
    }

    /**
     * Delete every directory a *clearable* [id] owns (per-project cache subdirs + shared dirs), and
     * return the bytes freed. A no-op returning 0 for a non-clearable id ([PROJECTS]/[OTHER]) or an
     * unknown id — the guarantee that clearing never removes source, config, or keystores.
     */
    fun clearCategory(id: String, projectRoots: List<Path>, sharedRoot: Path): Long {
        if (id !in CLEARABLE) return 0L
        val targets = ArrayList<Path>()
        targets.addAll(sharedTargets(id, sharedRoot))
        val names = PROJECT_CACHE_CATEGORY.filterValues { it == id }.keys
        for (proj in projectRoots) {
            val cacheRoot = proj.resolve(".platform").resolve("caches")
            for (name in names) targets.add(cacheRoot.resolve(name))
        }
        var freed = 0L
        for (target in targets) freed += deleteTree(target)
        return freed
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** The category for a file under `<cacheRoot>/<name>/…`, or null when it isn't a mapped cache file. */
    private fun cacheCategoryOf(cacheRoot: Path, file: Path): String? {
        if (!file.startsWith(cacheRoot)) return null
        val rel = cacheRoot.relativize(file)
        if (rel.nameCount < 1) return null
        return PROJECT_CACHE_CATEGORY[rel.getName(0).toString()]
    }

    private fun sizeOf(file: Path): Long = runCatching { Files.size(file) }.getOrDefault(0L)

    /** Sum of regular-file sizes in a tree (or the size of a single regular file); 0 if absent. */
    private fun treeSize(path: Path): Long {
        if (!Files.exists(path)) return 0L
        if (Files.isRegularFile(path)) return sizeOf(path)
        var total = 0L
        runCatching {
            Files.walk(path).use { s -> s.filter { Files.isRegularFile(it) }.forEach { total += sizeOf(it) } }
        }
        return total
    }

    /** Bytes under [storageRoot] excluding every subtree/file in [counted] (already attributed). */
    private fun otherSize(storageRoot: Path, counted: Set<Path>): Long {
        if (!Files.isDirectory(storageRoot)) return 0L
        var total = 0L
        runCatching {
            Files.walkFileTree(storageRoot, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir.normalize() in counted) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (file.normalize() !in counted) total += runCatching { attrs.size() }.getOrDefault(0L)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            })
        }
        return total
    }

    /** Size a tree then delete it (reverse order so dirs empty before removal); returns bytes freed. */
    private fun deleteTree(path: Path): Long {
        if (!Files.exists(path)) return 0L
        val freed = treeSize(path)
        runCatching {
            Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
        return freed
    }
}
