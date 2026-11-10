package dev.ide.android.support.pack

import dev.ide.android.support.tasks.DEX_CACHE_FORMAT
import dev.ide.android.support.tasks.DexArchives
import java.nio.file.Files
import java.nio.file.Path
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Where already-dexed libraries come from before the build dexes them itself. Consulted by
 * [dev.ide.android.support.tasks.SharedLibraryDexer] for the libraries missing from the shared dex cache, only
 * when a library's cache key is its own content (debug, minSdk 26+, no core-library desugaring): then a bucket
 * dexed anywhere with the same D8 is the bucket this build would make.
 */
fun interface DexPackSource {
    /** One library the build would otherwise dex: its content hash, its jar, and the jar's `.class` entries. */
    class Wanted(val hash: String, val jar: Path, val classes: Set<String>)

    /**
     * Install into [tagDir] (the shared dex cache's directory for the build's cache tag) every library among
     * [wanted] the source has. Returns how many it installed. Must not throw: a failure leaves those libraries to
     * be dexed locally.
     */
    fun fetch(wanted: List<Wanted>, tagDir: Path, log: (String) -> Unit): Int

    companion object {
        /** Ask each of [sources] in turn for what the ones before it didn't provide. */
        fun chain(vararg sources: DexPackSource?): DexPackSource {
            val list = sources.filterNotNull()
            return DexPackSource { wanted, tagDir, log ->
                var left = wanted
                var n = 0
                for (src in list) {
                    if (left.isEmpty()) break
                    n += src.fetch(left, tagDir, log)
                    left = left.filterNot { DexArchives.bucketVerified(tagDir.resolve(it.hash)) }
                }
                n
            }
        }

        /**
         * Install [dexes] (file name to bytes: a jar's merged `classes*.dex`) as [w]'s bucket in [tagDir], after
         * checking they define every class [w]'s jar holds, so nothing installed can drop a class out of an app.
         */
        fun installMerged(dexes: Map<String, ByteArray>, w: Wanted, tagDir: Path): Boolean {
            val target = tagDir.resolve(w.hash)
            if (DexArchives.bucketVerified(target)) return true
            if (dexes.isEmpty()) return false
            val defined = HashSet<String>()
            for (bytes in dexes.values) defined += DexClasses.namesOf(bytes) ?: return false
            if (!w.classes.filter(DexArchives::dexable).all { it in defined }) return false
            Files.createDirectories(tagDir)
            val stage = Files.createTempDirectory(tagDir, ".dex-${w.hash}-")
            try {
                val bucket = stage.resolve("bucket")
                Files.createDirectories(bucket)
                for ((name, bytes) in dexes) Files.write(bucket.resolve(name), bytes)
                Files.write(bucket.resolve(DexArchives.MERGED_MARKER), byteArrayOf())
                DexArchives.markBucketVerified(bucket, dexes.size)
                DexArchives.replaceInCache(bucket, target)
                return DexArchives.bucketVerified(target)
            } finally {
                DexArchives.clearDir(stage)
            }
        }
    }
}

/** A library version as the resolved-dependency cache stores it: `resolved-deps/<group path>/<artifact>/<version>/<file…>`. */
data class LibraryCoordinate(val group: String, val artifact: String, val version: String) {
    /** `<group path>/<artifact>/<version>`, the Maven repository directory. */
    val dir: String get() = "${group.replace('.', '/')}/$artifact/$version"

    override fun toString() = "$group:$artifact:$version"

    companion object {
        private const val ROOT = "resolved-deps"

        /**
         * The coordinate a resolved jar belongs to and the jar's path inside that version's directory (`foo-1.0.jar`,
         * or `foo-1.0-exploded/classes.jar` for an AAR), or null for a jar that didn't come from a Maven repository
         * (the IDE's bundled stdlib, a module's `libs/` jar).
         */
        fun of(jar: Path): Pair<LibraryCoordinate, String>? {
            val parts = jar.toAbsolutePath().normalize().map { it.toString() }
            val root = parts.lastIndexOf(ROOT).takeIf { it >= 0 } ?: return null
            val rel = parts.subList(root + 1, parts.size)
            // <group…>/<artifact>/<version>/<artifact>-<version>…: find the version directory from the end.
            for (v in rel.size - 2 downTo 2) {
                val artifact = rel[v - 1]
                val version = rel[v]
                if (rel[v + 1].startsWith("$artifact-$version")) {
                    val group = rel.subList(0, v - 1).joinToString(".")
                    return LibraryCoordinate(group, artifact, version) to rel.subList(v + 1, rel.size).joinToString("/")
                }
            }
            return null
        }
    }
}

/**
 * The public dex repository ([LibraryPack.DEFAULT_BASE_URL]): Maven Central's layout, holding dex instead of jars.
 * One [SignedArchive] per library version at `dex/<flavor>/<group path>/<artifact>/<version>/<artifact>-<version>.dex.zip`,
 * holding the dex of each jar the version ships (one for a jar; the `classes.jar` and any `libs/` jars of an
 * AAR) and, in its signed manifest, each jar's content hash. A library nobody published is a 404, and the build
 * dexes it as before.
 *
 * Each jar's dex is already MERGED (`classes.dex`, …, as `mergeExtDex` would make it), so a phone writes a file or
 * two per library instead of thousands and its build passes them through without merging. Built for minSdk 26
 * ([FLAVOR]): dex for 26 runs on every later level, so any minSdk-26+ project can use it. A download is installed
 * only for a jar whose content hash the signed manifest names, and only when its dex defines every class that jar
 * holds, so nothing can drop a class out of the app.
 */
class DexRepository(
    private val baseUrl: String = LibraryPack.DEFAULT_BASE_URL,
    private val publicKey: PublicKey = LibraryPack.publicKey(),
    private val net: LibraryPack.Net = LibraryPack.HttpNet,
    private val parallel: Int = 6,
    private val retryAfterMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : DexPackSource {

    /** Artifacts that weren't there (or didn't verify), so a build doesn't ask for them again for a while. */
    private val missing = ConcurrentHashMap<String, Long>()

    override fun fetch(wanted: List<DexPackSource.Wanted>, tagDir: Path, log: (String) -> Unit): Int = runCatching {
        val byArtifact = wanted.mapNotNull { w -> LibraryCoordinate.of(w.jar)?.let { (c, _) -> c to w } }
            .groupBy({ it.first }, { it.second })
        if (byArtifact.isEmpty()) return 0
        Files.createDirectories(tagDir)
        val t0 = System.nanoTime()
        val pool = Executors.newFixedThreadPool(parallel.coerceIn(1, byArtifact.size))
        val installed = try {
            pool.invokeAll(byArtifact.map { (coordinate, jars) ->
                Callable { runCatching { installArtifact(coordinate, jars, tagDir) }.getOrDefault(0) }
            }).sumOf { it.get() }
        } finally {
            pool.shutdownNow()
        }
        val summary = "dex repository: $installed of ${wanted.size} library jar(s) arrived pre-dexed in ${(System.nanoTime() - t0) / 1_000_000}ms"
        log(summary)
        repoLog.info(summary)
        installed
    }.getOrDefault(0)

    private fun installArtifact(coordinate: LibraryCoordinate, jars: List<DexPackSource.Wanted>, tagDir: Path): Int {
        val url = "${baseUrl.trimEnd('/')}/${pathOf(coordinate)}"
        missing[url]?.let { at -> if (clock() - at < retryAfterMs) return 0 }
        val opened = net.get(url)?.let { SignedArchive.open(it, publicKey) }
        if (opened == null) { missing[url] = clock(); return 0 }
        // "jar <folder> <content hash> <path in the version dir>"
        val folderOfHash = opened.attributes.mapNotNull { line ->
            line.split(' ').takeIf { it.size >= 4 && it[0] == "jar" }?.let { it[2] to it[1] }
        }.toMap()
        return jars.count { w ->
            val folder = folderOfHash[w.hash]
            val ok = folder != null && install(folder, opened.files, w, tagDir)
            // A version that's published but not installed is worth a line: either this jar's bytes differ from
            // the published one, or its dex didn't cover every class (the install check refused it).
            if (!ok) repoLog.info("dex repository: $coordinate not installed for ${w.jar.fileName}: " +
                if (folder == null) "the published archive is for different jar contents" else "its dex doesn't define every class of the jar")
            ok
        }
    }

    /**
     * Install the merged dex in [folder] as [w]'s bucket, after checking it defines every class the jar on this
     * device holds: the signed manifest already ties the dex to this jar's content hash, and this keeps a
     * publishing mistake from ever dropping a class out of an app.
     */
    private fun install(folder: String, files: Map<String, ByteArray>, w: DexPackSource.Wanted, tagDir: Path): Boolean {
        val dexes = files.filterKeys { it.startsWith("$folder/") && it.endsWith(".dex") && '/' !in it.removePrefix("$folder/") }
            .mapKeys { it.key.substringAfterLast('/') }
        return DexPackSource.installMerged(dexes, w, tagDir)
    }

    companion object {
        /** The D8 build and mode the repository's dex was made with; a different D8 publishes under a new flavor. */
        const val FLAVOR = "$DEX_CACHE_FORMAT/minApi26-debug-merged"

        /** The minSdk the repository's dex is made for, and the lowest a project can be to use it. */
        const val MIN_API = 26

        /** Also to the app log (logcat on device), where a repository that stopped helping shows up without a build open. */
        private val repoLog by lazy { dev.ide.platform.log.Log.logger("dexrepo") }

        /** Where [coordinate]'s archive lives, relative to the repository root. */
        fun pathOf(coordinate: LibraryCoordinate): String =
            "dex/$FLAVOR/${coordinate.dir}/${coordinate.artifact}-${coordinate.version}.dex.zip"

        /**
         * A source that reads the repository at whatever [baseUrl] says now (null = off), so a settings change
         * applies on the next build. One [DexRepository] per URL, so each keeps its memory of what's missing.
         */
        fun at(baseUrl: () -> String?): DexPackSource {
            val byUrl = ConcurrentHashMap<String, DexRepository>()
            return DexPackSource { wanted, tagDir, log ->
                val url = runCatching(baseUrl).getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@DexPackSource 0
                byUrl.computeIfAbsent(url) { DexRepository(baseUrl = it) }.fetch(wanted, tagDir, log)
            }
        }

        /** Whether every library version among [jars] that has code already has an archive under [repoRoot]. */
        fun isPublished(jars: List<Path>, repoRoot: Path): Boolean = jars.all { jar ->
            val c = LibraryCoordinate.of(jar)?.first ?: return@all true
            Files.exists(repoRoot.resolve(pathOf(c))) || DexArchives.classNamesOf(jar).isEmpty()
        }

        /**
         * Publisher side: write an archive for every library version among [jars] whose buckets a minSdk-26 debug
         * build verified in [tagDir], under [repoRoot] at [pathOf]. An archive already in [repoRoot] is left as it
         * is: a published version never changes. Returns the coordinates written.
         */
        fun publish(
            jars: List<Path>, tagDir: Path, repoRoot: Path, key: PrivateKey,
            /** Merge a library's per-class dex into indexed dex in the given directory (D8, as `mergeExtDex` would). */
            merge: (dexes: List<Path>, out: Path) -> Boolean,
        ): List<LibraryCoordinate> {
            val byArtifact = jars.distinct().mapNotNull { jar -> LibraryCoordinate.of(jar)?.let { (c, inner) -> Triple(c, inner, jar) } }
                .groupBy { it.first }
            val written = ArrayList<LibraryCoordinate>()
            for ((coordinate, entries) in byArtifact) {
                val out = repoRoot.resolve(pathOf(coordinate))
                if (Files.exists(out)) continue
                val attributes = arrayListOf("coordinate $coordinate", "flavor $FLAVOR")
                val files = LinkedHashMap<String, ByteArray>()
                var complete = true
                for ((i, entry) in entries.sortedBy { it.second }.withIndex()) {
                    val (_, inner, jar) = entry
                    if (DexArchives.classNamesOf(jar).isEmpty()) continue       // a resource-only AAR's classes.jar
                    val hash = DexArchives.contentHash(jar)
                    val bucket = tagDir.resolve(hash)
                    if (!DexArchives.bucketVerified(bucket)) { complete = false; break }
                    val folder = "j$i"
                    val out = Files.createTempDirectory("dexrepo-merge")
                    try {
                        val perClass = LibraryPack.filesOf(bucket).map { it.second }.filter { it.toString().endsWith(".dex") }
                        if (!merge(perClass, out)) { complete = false; break }
                        val merged = LibraryPack.filesOf(out).filter { it.first.endsWith(".dex") }
                        if (merged.isEmpty()) { complete = false; break }
                        attributes += "jar $folder $hash $inner"
                        for ((rel, file) in merged) files["$folder/${rel.substringAfterLast('/')}"] = Files.readAllBytes(file)
                    } finally {
                        out.toFile().deleteRecursively()
                    }
                }
                if (!complete || files.isEmpty()) continue
                Files.createDirectories(out.parent)
                Files.write(out, SignedArchive.write(attributes, files, key))
                written += coordinate
            }
            return written
        }
    }
}

/** The classes a dex file defines, read from its header and class definitions (no dex library needed). */
internal object DexClasses {
    /** `a/b/C.class` for every class [dex] defines, or null when it isn't a dex file this can read. */
    fun namesOf(dex: ByteArray): Set<String>? = runCatching {
        val b = java.nio.ByteBuffer.wrap(dex).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (dex.size < 0x70 || dex[0] != 'd'.code.toByte() || dex[1] != 'e'.code.toByte() || dex[2] != 'x'.code.toByte()) return null
        val stringIdsOff = b.getInt(0x3C)
        val typeIdsOff = b.getInt(0x44)
        val classDefsSize = b.getInt(0x60)
        val classDefsOff = b.getInt(0x64)
        val out = HashSet<String>(classDefsSize * 2)
        for (i in 0 until classDefsSize) {
            val typeIdx = b.getInt(classDefsOff + i * 32)
            val descriptorIdx = b.getInt(typeIdsOff + typeIdx * 4)
            var p = b.getInt(stringIdsOff + descriptorIdx * 4)
            while (dex[p].toInt() and 0x80 != 0) p++            // skip the uleb128 utf16 length
            p++
            var end = p
            while (dex[end].toInt() != 0) end++
            val descriptor = String(dex, p, end - p, Charsets.UTF_8)   // class descriptors are ASCII in practice
            if (descriptor.startsWith("L") && descriptor.endsWith(";")) out += descriptor.substring(1, descriptor.length - 1) + ".class"
        }
        out
    }.getOrNull()
}

/**
 * Dex the app ships for the jars it adds to every user app itself (the bundled kotlin-stdlib, the app-log
 * bridge): they have no Maven coordinate, so the repository can't serve them. [jars] maps each jar to its
 * pre-dexed zip (D8's `classes*.dex`, made at app build time with the same D8 and settings as the repository); a
 * build's jar is matched by content, so it is found whatever path the build gives it.
 */
class BundledDex(private val jars: Map<Path, Path>) : DexPackSource {
    private val zipOfHash: Map<String, Path> by lazy {
        jars.mapNotNull { (jar, zip) -> runCatching { DexArchives.contentHash(jar) to zip }.getOrNull() }.toMap()
    }

    override fun fetch(wanted: List<DexPackSource.Wanted>, tagDir: Path, log: (String) -> Unit): Int = runCatching {
        var n = 0
        for (w in wanted) {
            val zip = zipOfHash[w.hash] ?: continue
            val dexes = java.util.zip.ZipFile(zip.toFile()).use { z ->
                z.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".dex") && '/' !in it.name }
                    .associate { it.name to z.getInputStream(it).use { s -> s.readBytes() } }
            }
            if (DexPackSource.installMerged(dexes, w, tagDir)) n++
        }
        if (n > 0) log("bundled dex: $n jar(s) the IDE ships arrived pre-dexed")
        n
    }.getOrDefault(0)
}
