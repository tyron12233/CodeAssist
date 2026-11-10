package dev.ide.core.index

import dev.ide.android.support.pack.LibraryCoordinate
import dev.ide.android.support.pack.LibraryPack
import dev.ide.android.support.pack.SignedArchive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap

/**
 * The path-free identity of a library jar: a hash of its bytes. It keys the jar's index segments
 * (`jar-<id>.seg`) in place of the default path + modified-time key, so a segment made for the same jar anywhere
 * (another project, another device, the publisher) is the segment this device looks up, which is what lets the
 * index repository ([LibraryIndexRepository]) ship them. IntelliJ's shared indexes work the same way, per file.
 *
 * Hashing reads the whole jar, so results are cached in [cacheFile] by path, size and modified time: a resync
 * over unchanged libraries only stats them.
 */
class JarContentIds(private val cacheFile: Path) {
    private val entries = HashMap<String, String>()
    private var loaded = false
    private var dirty = false

    /** The id of [jar], or null when it can't be read. */
    @Synchronized
    fun idOf(jar: Path): String? {
        load()
        val path = jar.toAbsolutePath().normalize().toString()
        val sig = runCatching { "${Files.size(jar)}:${Files.getLastModifiedTime(jar).toMillis()}" }.getOrNull() ?: return null
        entries[path]?.let { v -> if (v.substringBeforeLast(':') == sig) return v.substringAfterLast(':') }
        val id = compute(jar) ?: return null
        entries[path] = "$sig:$id"; dirty = true
        return id
    }

    @Synchronized
    fun flush() {
        if (!dirty) return
        runCatching {
            cacheFile.parent?.let { Files.createDirectories(it) }
            val tmp = cacheFile.resolveSibling(cacheFile.fileName.toString() + ".tmp")
            Files.write(tmp, entries.map { "${it.key}\t${it.value}" })
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING)
            dirty = false
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            if (Files.isRegularFile(cacheFile)) Files.readAllLines(cacheFile).forEach { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) entries[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
    }

    companion object {
        /** `sha-` and the first 128 bits of the SHA-256 of [jar]'s bytes. */
        fun compute(jar: Path): String? = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(jar).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            "sha-" + md.digest().take(16).joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}

/**
 * Prebuilt index segments for library versions, in the same public repository as their dex
 * ([LibraryPack.DEFAULT_BASE_URL]): one [SignedArchive] per version at
 * `index/<flavor>/<group path>/<artifact>/<version>/<artifact>-<version>.index.zip`, holding the segments of what
 * the version ships, each jar's (`jar-<content id>`) and, for an AAR, its `res/` folder's (`res-<aar content id>`),
 * as `<index id>/v<index version>/<key>.seg`, the shared index cache's own layout.
 *
 * The flavor names the segment format and a digest of the indexers (id and version) that built them ([flavorOf]),
 * so an IDE release that changes an indexer reads and publishes a flavor of its own instead of being served
 * segments its indexers can't use. Within a flavor a phone still installs only the (index, version) pairs it runs,
 * as IntelliJ treats a shared index chunk from a different indexer version.
 */
class LibraryIndexRepository(
    private val baseUrl: String = LibraryPack.DEFAULT_BASE_URL,
    private val publicKey: PublicKey = LibraryPack.publicKey(),
    private val net: LibraryPack.Net = LibraryPack.HttpNet,
    private val retryAfterMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val missing = ConcurrentHashMap<String, Long>()

    /**
     * Download into [cacheRoot] the segments of every artifact in [keys] (a library jar or `res/` folder, to its
     * segment key) that lacks one of [indexes] (index id to version). Returns how many artifacts it completed.
     * Never throws.
     */
    fun fetch(keys: Map<Path, String>, cacheRoot: Path, indexes: Map<String, Int>, log: (String) -> Unit = {}): Int = runCatching {
        // Only artifacts the repository could have (a Maven coordinate); the IDE's own jars come with the app.
        val wanted = keys.filter { (path, key) ->
            LibraryCoordinate.of(path) != null && indexes.any { (ix, v) -> !Files.exists(cacheRoot.resolve(segmentPath(ix, v, key))) }
        }
        val byArtifact = wanted.mapNotNull { (path, key) -> LibraryCoordinate.of(path)?.let { (c, _) -> c to key } }
            .groupBy({ it.first }, { it.second })
        // Jars the editor indexes that the repository can't serve, named so a gap shows up in the log.
        val unmapped = keys.keys.filter { LibraryCoordinate.of(it) == null }
        if (unmapped.isNotEmpty()) log("index repository: ${unmapped.size} artifact(s) without a Maven coordinate, e.g. ${unmapped.take(5).joinToString { it.toString() }}")
        if (byArtifact.isEmpty()) return 0
        val flavor = flavorOf(indexes)
        val t0 = System.nanoTime()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6.coerceAtMost(byArtifact.size))
        val done = try {
            pool.invokeAll(byArtifact.map { (c, wantedKeys) ->
                java.util.concurrent.Callable { runCatching { install(c, flavor, wantedKeys.toSet(), cacheRoot, indexes) }.getOrDefault(0) }
            }).sumOf { it.get() }
        } finally {
            pool.shutdownNow()
        }
        log("index repository: $done of ${wanted.size} library artifact(s) arrived pre-indexed in ${(System.nanoTime() - t0) / 1_000_000}ms")
        done
    }.getOrDefault(0)

    private fun install(coordinate: LibraryCoordinate, flavor: String, wantedKeys: Set<String>, cacheRoot: Path, indexes: Map<String, Int>): Int {
        val url = "${baseUrl.trimEnd('/')}/${pathOf(coordinate, flavor)}"
        missing[url]?.let { at -> if (clock() - at < retryAfterMs) return 0 }
        val opened = net.get(url)?.let { SignedArchive.open(it, publicKey) }
        if (opened == null) { missing[url] = clock(); return 0 }
        // "artifact <segment key> <path in the version dir>"
        val published = opened.attributes.mapNotNull { it.split(' ').takeIf { p -> p.size >= 2 && p[0] == "artifact" }?.get(1) }.toSet()
        var completed = 0
        for (key in wantedKeys intersect published) {
            var all = true
            for ((ix, v) in indexes) {
                val rel = segmentPath(ix, v, key)
                val target = cacheRoot.resolve(rel)
                if (Files.exists(target)) continue
                val bytes = opened.files[rel]
                if (bytes == null) { all = false; continue }      // not published for this index: built here
                Files.createDirectories(target.parent)
                val tmp = Files.createTempFile(target.parent, ".dl-", ".seg")
                Files.write(tmp, bytes)
                runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE) }
                    .onFailure { Files.deleteIfExists(tmp) }
            }
            if (all) completed++
        }
        return completed
    }

    companion object {
        /** The segment file format the repository's segments are written in (`Segment.MAGIC`, "IDX3"). */
        private const val SEGMENT_FORMAT = "idx3"

        /** The flavor for segments built by [indexes] (index id to version): the format plus a digest of the set. */
        fun flavorOf(indexes: Map<String, Int>): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(indexes.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }.toByteArray())
            return SEGMENT_FORMAT + "-" + md.digest().take(5).joinToString("") { "%02x".format(it) }
        }

        fun pathOf(coordinate: LibraryCoordinate, flavor: String): String =
            "index/$flavor/${coordinate.dir}/${coordinate.artifact}-${coordinate.version}.index.zip"

        /** Where the shared index cache keeps [index] version [version]'s segment of the artifact keyed [key]. */
        fun segmentPath(index: String, version: Int, key: String): String = "$index/v$version/$key.seg"

        /** The segment key of a library jar with content [id] (the index service's `jar-<stable id>`). */
        fun jarKey(id: String) = "jar-$id"

        /** The segment key of a library `res/` folder whose AAR has content [id] (`res-<stable id>`). */
        fun resKey(id: String) = "res-$id"

        /** Whether every library version among [keys]' artifacts already has an archive for [indexes]' flavor. */
        fun isPublished(keys: Map<Path, String>, repoRoot: Path, indexes: Map<String, Int>): Boolean {
            val flavor = flavorOf(indexes)
            return keys.keys.all { p -> LibraryCoordinate.of(p)?.first?.let { Files.exists(repoRoot.resolve(pathOf(it, flavor))) } ?: true }
        }

        /**
         * Publisher side: archive, under [repoRoot], the segments [cacheRoot] holds for every library version among
         * [keys]' artifacts (jar or `res/` folder, to segment key), for every one of [indexes]. A version already
         * in [repoRoot] is left alone, and a version whose segments aren't all built is skipped. Returns the
         * coordinates written.
         */
        fun publish(keys: Map<Path, String>, cacheRoot: Path, repoRoot: Path, key: PrivateKey, indexes: Map<String, Int>): List<LibraryCoordinate> {
            val flavor = flavorOf(indexes)
            val byArtifact = keys.mapNotNull { (path, segKey) -> LibraryCoordinate.of(path)?.let { (c, inner) -> Triple(c, inner, segKey) } }
                .groupBy { it.first }
            val written = ArrayList<LibraryCoordinate>()
            for ((coordinate, artifacts) in byArtifact) {
                val out = repoRoot.resolve(pathOf(coordinate, flavor))
                if (Files.exists(out)) continue
                val attributes = arrayListOf("coordinate $coordinate", "flavor $flavor")
                val files = LinkedHashMap<String, ByteArray>()
                var complete = true
                for ((_, inner, segKey) in artifacts.sortedBy { it.second }) {
                    attributes += "artifact $segKey $inner"
                    for ((ix, v) in indexes) {
                        val rel = segmentPath(ix, v, segKey)
                        val f = cacheRoot.resolve(rel)
                        if (!Files.isRegularFile(f)) { complete = false; break }
                        files[rel] = Files.readAllBytes(f)
                    }
                    if (!complete) break
                }
                if (!complete) continue
                Files.createDirectories(out.parent)
                Files.write(out, SignedArchive.write(attributes, files, key))
                written += coordinate
            }
            return written
        }
    }
}
