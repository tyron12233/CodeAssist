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

    /** Directory an `.aar` is exploded into (e.g. its `classes.jar`, `res/`, `assets/`). */
    fun explodedDir(c: Coordinate): Path =
        base.resolve(c.group.replace('.', '/')).resolve(c.name).resolve(c.version).resolve("${c.name}-${c.version}-exploded")
}
