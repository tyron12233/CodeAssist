package dev.ide.deps.impl

import dev.ide.platform.createDirectories
import dev.ide.platform.deleteFile
import dev.ide.platform.fileInfo
import dev.ide.platform.listDirectory
import dev.ide.platform.moveFile
import dev.ide.platform.readFile
import dev.ide.platform.writeFileAtomically
import dev.ide.model.Coordinate
import dev.ide.platform.epochMillis

/**
 * On-disk artifact store under `<root>/.platform/caches/resolved-deps`, laid out exactly like a Maven
 * repository (`group/as/path/name/version/name-version.ext`). Because the layout mirrors a repo, the
 * cache *is* the offline repository: a coordinate present here resolves with no network at all.
 *
 * Writes go through a temp file + atomic move so a crash mid-download never leaves a truncated jar that
 * a later run would mistake for a complete one.
 *
 * Paths are STRINGS, here and through the resolver: `java.nio.file.Path` cannot be named from common code,
 * and a path is a string on every platform that has one. The six-operation portable file system underneath
 * is `:kotlin-classfile`'s, which is also where the atomic whole-file write lives.
 */
class ResolverCache(val root: String) {

    private val base: String = "$root/.platform/caches/resolved-deps"

    /**
     * Maven-layout relative path for an artifact, e.g. `com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar`.
     *
     * [classifier] is passed EXPLICITLY rather than read off [c]: a coordinate carrying one still has a
     * single, classifier-less `.pom` and `.module` (a classifier names a secondary artifact of the module,
     * not a separate module), so defaulting it would send every metadata probe to a path that 404s.
     */
    fun relativePath(c: Coordinate, ext: String, classifier: String? = null): String {
        val groupPath = c.group.replace('.', '/')
        val suffix = if (classifier.isNullOrEmpty()) "" else "-$classifier"
        return "$groupPath/${c.name}/${c.version}/${c.name}-${c.version}$suffix.$ext"
    }

    fun fileFor(relative: String): String = "$base/$relative"

    fun exists(relative: String): Boolean = isRegularFile(fileFor(relative))

    fun read(relative: String): ByteArray? {
        val p = fileFor(relative)
        return if (isRegularFile(p)) readFile(p) else null
    }

    /** Persist [bytes] at [relative] atomically and return the final path. */
    fun write(relative: String, bytes: ByteArray): String {
        val target = fileFor(relative)
        // `writeFileAtomically` creates the parent, writes a sibling temporary and renames it over the
        // target — the same temp-then-move this used to spell out.
        check(writeFileAtomically(target, bytes)) { "could not write $target" }
        return target
    }

    /**
     * Stream an artifact into [relative] without buffering it in heap: [download] is handed the temp `.part`
     * path and writes to it (e.g. via [ArtifactFetcher.fetchTo]), returning true if it produced content.
     * On true the temp is atomically moved into place (same crash-safety as [write]); on false (resource
     * absent) the temp is removed and null returned, so the caller can fall through to the next repo. The
     * temp is always cleaned up if [download] throws.
     */
    fun writeStreaming(relative: String, download: (String) -> Boolean): String? {
        val target = fileFor(relative)
        createDirectories(parentOf(target))
        val tmp = "$target.part"
        val ok = try {
            download(tmp)
        } catch (t: Throwable) {
            deleteFile(tmp); throw t
        }
        if (!ok) { deleteFile(tmp); return null }
        if (!moveFile(tmp, target)) { deleteFile(tmp); return null }
        return target
    }

    /** Directory an `.aar` is exploded into (e.g. its `classes.jar`, `res/`, `assets/`). A classifier joins the
     *  directory name, so two secondary AARs of one `group:name:version` don't explode over each other. */
    fun explodedDir(c: Coordinate): String {
        val suffix = if (c.classifier.isNullOrEmpty()) "" else "-${c.classifier}"
        return "$base/${c.group.replace('.', '/')}/${c.name}/${c.version}/${c.name}-${c.version}$suffix-exploded"
    }

    // --- cached-version management ---------------------------------------------------------------
    // The Dependencies screen lets the user see which versions of an artifact are downloaded (each version
    // is a directory under `<groupPath>/<name>/`) and delete old ones to reclaim disk — the fine-grained
    // counterpart to the Storage screen's bulk "clear dependencies".

    /** The directory holding every cached version of `group:name` (a Maven `<groupPath>/<name>` dir). */
    private fun artifactDir(group: String, name: String): String =
        "$base/${group.replace('.', '/')}/$name"

    /**
     * Every version of `group:name` present on disk, each with the total bytes it occupies (artifacts +
     * exploded AAR), newest-first. Empty when nothing is cached for the artifact.
     */
    fun cachedVersions(group: String, name: String): List<Pair<String, Long>> {
        val dir = artifactDir(group, name)
        if (!isDirectory(dir)) return emptyList()
        return listDirectory(dir).filter { isDirectory(it) }
            .map { nameOf(it) to treeSize(it) }
            .sortedWith(compareByDescending(VERSION_ORDER) { it.first })
    }

    /**
     * Delete the cached [version] of `group:name` (its whole version dir, incl. the exploded AAR) and any
     * negative-cache sidecars for it. Returns true when a version dir was present and removed. A build that
     * still needs the version simply re-downloads it.
     */
    fun deleteVersion(group: String, name: String, version: String): Boolean {
        val groupPath = group.replace('.', '/')
        val dir = "$base/$groupPath/$name/$version"
        val existed = isDirectory(dir)
        deleteTree(dir)
        deleteTree("$missBase/$groupPath/$name/$version")
        return existed
    }

    /** Sum of regular-file sizes under [path] (0 if absent). */
    private fun treeSize(path: String): Long {
        val info = fileInfo(path) ?: return 0L
        if (!info.isDirectory) return info.size
        return listDirectory(path).sumOf { treeSize(it) }
    }

    /** Recursively delete [path], children first so a directory is empty before it goes; a no-op if absent. */
    private fun deleteTree(path: String) {
        val info = fileInfo(path) ?: return
        if (info.isDirectory) for (child in listDirectory(path)) deleteTree(child)
        deleteFile(path)
    }

    // --- the portable file system, in the shapes this class asks in ------------------------------

    private fun isRegularFile(path: String): Boolean = fileInfo(path)?.isDirectory == false

    private fun isDirectory(path: String): Boolean = fileInfo(path)?.isDirectory == true

    private fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")

    private fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    // --- negative cache --------------------------------------------------------------------------
    // A genuine 404 (the resource is absent from every repo, NOT a network error) is remembered so a later
    // open doesn't re-probe the network for something that doesn't exist — the dominant repeat-download cause
    // is `-sources.jar`s, which most libraries never publish. A miss expires after [MISS_TTL_MS] so a since-
    // published artifact is eventually picked up, and the explicit "Retry" path clears all misses. Network
    // ERRORS are never recorded here (they're transient); only clean 404s are.

    private val missBase: String = "$root/.platform/caches/resolved-deps-misses"

    private fun missFileFor(relative: String): String = "$missBase/$relative.miss"

    /** True if [relative] was recorded absent within the TTL (so skip the network). Expired entries are
     *  deleted and treated as unknown, so the artifact is re-probed once. */
    fun isKnownMissing(relative: String, now: Long = epochMillis()): Boolean {
        val f = missFileFor(relative)
        if (!isRegularFile(f)) return false
        val ts = readFile(f)?.decodeToString()?.trim()?.toLongOrNull() ?: return false
        if (now - ts > MISS_TTL_MS) { deleteFile(f); return false }
        return true
    }

    /** Record [relative] as absent (a confirmed 404 across every repo). Best-effort; a write failure just
     *  means the miss isn't remembered (the artifact is re-probed next time — never an error). */
    fun recordMissing(relative: String, now: Long = epochMillis()) {
        runCatching { writeFileAtomically(missFileFor(relative), now.toString().encodeToByteArray()) }
    }

    /** Drop all negative-cache entries — called when the user explicitly retries, so known-misses are re-probed. */
    fun clearMisses() {
        runCatching { deleteTree(missBase) }
    }

    private companion object {
        const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000   // 7 days

        private val NUM = Regex("\\d+")

        /** Ascending version order: numeric segments compared as numbers (so 10 > 9), with the raw string as
         *  a stable tiebreak. Best-effort for display ordering — not a full semver pre-release ranking. */
        val VERSION_ORDER: Comparator<String> = Comparator { a, b ->
            val na = NUM.findAll(a).map { it.value.toLongOrNull() ?: 0L }.toList()
            val nb = NUM.findAll(b).map { it.value.toLongOrNull() ?: 0L }.toList()
            var result = 0
            for (i in 0 until maxOf(na.size, nb.size)) {
                val c = (na.getOrElse(i) { 0L }).compareTo(nb.getOrElse(i) { 0L })
                if (c != 0) { result = c; break }
            }
            if (result != 0) result else a.compareTo(b)
        }
    }
}
