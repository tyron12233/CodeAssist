package dev.ide.model

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The directories of this module that hold derived output rather than project content, absolute and
 * normalized: every content root declared with [ContentRole.EXCLUDED] (`bin/`, `obj/`), and the module's own
 * `build/` directory.
 *
 * They only matter where one sits inside a source root, which happens when a root is the module's own
 * directory (a language with no source folder). Whatever walks a root then has to stay out of them, which is
 * what [contentFilesUnder] does with this set.
 */
fun Module.derivedOutputDirs(): Set<Path> {
    val out = HashSet<Path>()
    sourceSets.flatMap { it.contentRoots }.filter { ContentRole.EXCLUDED in it.roles }
        .mapNotNullTo(out) { runCatching { Paths.get(it.dir.path).toAbsolutePath().normalize() }.getOrNull() }
    runCatching { Paths.get(dir.path).resolve("build").toAbsolutePath().normalize() }.getOrNull()?.let(out::add)
    return out
}

/**
 * Every file under [root], skipping the subtrees of [excluded] (absolute, normalized directories, as
 * [derivedOutputDirs] returns them). [root] itself is always walked, so a generated-sources root that sits
 * inside a build directory still lists. Empty when [root] is not a directory; an unreadable entry is skipped.
 *
 * What a sweep over a module's roots should use instead of `Files.walk`.
 */
fun contentFilesUnder(root: Path, excluded: Set<Path>): List<Path> {
    val out = ArrayList<Path>()
    if (!Files.isDirectory(root)) return out
    val start = root.toAbsolutePath().normalize()
    runCatching {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (excluded.isEmpty()) return FileVisitResult.CONTINUE
                val d = dir.toAbsolutePath().normalize()
                return if (d != start && d in excluded) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                out.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
    }
    return out
}
