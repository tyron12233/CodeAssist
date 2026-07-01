package dev.ide.deps.impl

import dev.ide.model.Coordinate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * On-disk artifact store under `<root>/.platform/caches/resolved-deps`, laid out exactly like a Maven
 * repository (`group/as/path/name/version/name-version.ext`). Because the layout mirrors a repo, the
 * cache *is* the offline repository: a coordinate present here resolves with no network at all.
 *
 * Writes go through a temp file + atomic move so a crash mid-download never leaves a truncated jar that
 * a later run would mistake for a complete one.
 */
class ResolverCache(val root: Path) {

    private val base: Path = root.resolve(".platform").resolve("caches").resolve("resolved-deps")

    /** Maven-layout relative path for an artifact, e.g. `com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar`. */
    fun relativePath(c: Coordinate, ext: String, classifier: String? = null): String {
        val groupPath = c.group.replace('.', '/')
        val suffix = if (classifier.isNullOrEmpty()) "" else "-$classifier"
        return "$groupPath/${c.name}/${c.version}/${c.name}-${c.version}$suffix.$ext"
    }

    fun fileFor(relative: String): Path = base.resolve(relative)

    fun exists(relative: String): Boolean = Files.isRegularFile(fileFor(relative))

    fun read(relative: String): ByteArray? {
        val p = fileFor(relative)
        return if (Files.isRegularFile(p)) Files.readAllBytes(p) else null
    }

    /** Persist [bytes] at [relative] atomically and return the final path. */
    fun write(relative: String, bytes: ByteArray): Path {
        val target = fileFor(relative)
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "${target.fileName}.", ".part")
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    /**
     * Stream an artifact into [relative] without buffering it in heap: [download] is handed the temp `.part`
     * path and writes to it (e.g. via [ArtifactFetcher.fetchTo]), returning true if it produced content.
     * On true the temp is atomically moved into place (same crash-safety as [write]); on false (resource
     * absent) the temp is removed and null returned, so the caller can fall through to the next repo. The
     * temp is always cleaned up if [download] throws.
     */
    fun writeStreaming(relative: String, download: (Path) -> Boolean): Path? {
        val target = fileFor(relative)
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "${target.fileName}.", ".part")
        val ok = try {
            download(tmp)
        } catch (t: Throwable) {
            Files.deleteIfExists(tmp); throw t
        }
        if (!ok) { Files.deleteIfExists(tmp); return null }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    /** Directory an `.aar` is exploded into (e.g. its `classes.jar`, `res/`, `assets/`). */
    fun explodedDir(c: Coordinate): Path =
        base.resolve(c.group.replace('.', '/')).resolve(c.name).resolve(c.version).resolve("${c.name}-${c.version}-exploded")

    // --- negative cache --------------------------------------------------------------------------
    // A genuine 404 (the resource is absent from every repo, NOT a network error) is remembered so a later
    // open doesn't re-probe the network for something that doesn't exist — the dominant repeat-download cause
    // is `-sources.jar`s, which most libraries never publish. A miss expires after [MISS_TTL_MS] so a since-
    // published artifact is eventually picked up, and the explicit "Retry" path clears all misses. Network
    // ERRORS are never recorded here (they're transient); only clean 404s are.

    private val missBase: Path = root.resolve(".platform").resolve("caches").resolve("resolved-deps-misses")

    private fun missFileFor(relative: String): Path = missBase.resolve("$relative.miss")

    /** True if [relative] was recorded absent within the TTL (so skip the network). Expired entries are
     *  deleted and treated as unknown, so the artifact is re-probed once. */
    fun isKnownMissing(relative: String, now: Long = System.currentTimeMillis()): Boolean {
        val f = missFileFor(relative)
        if (!Files.isRegularFile(f)) return false
        val ts = runCatching { String(Files.readAllBytes(f)).trim().toLong() }.getOrNull() ?: return false
        if (now - ts > MISS_TTL_MS) { runCatching { Files.deleteIfExists(f) }; return false }
        return true
    }

    /** Record [relative] as absent (a confirmed 404 across every repo). Best-effort; a write failure just
     *  means the miss isn't remembered (the artifact is re-probed next time — never an error). */
    fun recordMissing(relative: String, now: Long = System.currentTimeMillis()) {
        runCatching {
            val f = missFileFor(relative)
            Files.createDirectories(f.parent)
            Files.write(f, now.toString().toByteArray())
        }
    }

    /** Drop all negative-cache entries — called when the user explicitly retries, so known-misses are re-probed. */
    fun clearMisses() {
        if (!Files.isDirectory(missBase)) return
        runCatching {
            Files.walk(missBase).use { s ->
                s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private companion object {
        const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000   // 7 days
    }
}
