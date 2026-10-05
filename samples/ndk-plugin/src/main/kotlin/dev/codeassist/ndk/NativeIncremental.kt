package dev.codeassist.ndk

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the dependency file clang writes beside an object under `-MD -MF <file>`.
 *
 * The format is a Makefile rule: `out.o: in.cpp a.h \` with a backslash continuing the line, and a space
 * inside a path written as `\ `. The first entry after the colon is the source itself; the rest are every
 * header the translation unit opened, the toolchain's own included, which is what makes an edit to any of
 * them visible to the next build.
 */
internal object DepFile {

    /** Every prerequisite of the rule in [text], in the order clang wrote them. Empty when there is no rule. */
    fun parse(text: String): List<String> {
        val joined = text.replace("\\\r\n", " ").replace("\\\n", " ")
        val colon = ruleColon(joined) ?: return emptyList()
        val out = ArrayList<String>()
        val current = StringBuilder()
        var i = colon + 1
        while (i < joined.length) {
            val c = joined[i]
            when {
                c == '\\' && i + 1 < joined.length && joined[i + 1] == ' ' -> { current.append(' '); i++ }
                c == '\n' || c == '\r' -> {
                    // A second rule (clang writes one per target with -MP) ends this one's list.
                    if (current.isNotEmpty()) out.add(current.toString())
                    return out
                }
                c.isWhitespace() -> if (current.isNotEmpty()) { out.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    /**
     * The colon that ends the target, skipping a drive letter's (`C:\x.o:`) and an escaped one. clang escapes
     * a space in the target the same way as in a prerequisite, so the first unescaped colon followed by
     * whitespace or the end of the text is the separator.
     */
    private fun ruleColon(text: String): Int? {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') { i += 2; continue }
            if (c == ':' && (i + 1 == text.length || text[i + 1].isWhitespace())) return i
            i++
        }
        return null
    }
}

/**
 * Which objects a native build has to remake, decided from what is on disk.
 *
 * An object is current when it exists, its dependency file exists, and neither the source nor any header the
 * dependency file names is newer than it. A dependency that no longer exists makes it stale as well: the
 * header was deleted or moved, and the compiler is the one that should say whether the source still builds.
 * A missing dependency file means a previous compile did not finish, so nothing about the object is trusted.
 */
internal object NativeIncremental {

    /** Whether [obj], compiled from [source] with its dependencies recorded in [depFile], must be rebuilt. */
    fun isStale(source: Path, obj: Path, depFile: Path, mtime: (Path) -> Long? = ::modified): Boolean {
        val built = mtime(obj) ?: return true
        val sourceTime = mtime(source) ?: return true
        if (sourceTime > built) return true
        val deps = readText(depFile)?.let(DepFile::parse) ?: return true
        for (dep in deps) {
            val time = mtime(source.resolveSibling(dep).normalize()) ?: return true
            if (time > built) return true
        }
        return false
    }

    /**
     * Whether the library at [output] has to be linked again: something was just compiled, the library is
     * missing or older than one of its objects, or the link itself changed (a different set of objects, other
     * libraries, a new toolchain), which [stamp] and [previousStamp] describe.
     */
    fun needsLink(
        compiledAny: Boolean,
        output: Path,
        objects: List<Path>,
        stamp: String,
        previousStamp: String?,
        mtime: (Path) -> Long? = ::modified,
    ): Boolean {
        if (compiledAny || stamp != previousStamp) return true
        val linked = mtime(output) ?: return true
        return objects.any { (mtime(it) ?: return true) > linked }
    }

    /** Every path the dependency files under [objDir] name, so a header outside the source dirs counts as an input. */
    fun recordedDependencies(objDir: Path): Set<String> {
        if (!Files.isDirectory(objDir)) return emptySet()
        val out = sortedSetOf<String>()
        Files.walk(objDir).use { walk ->
            walk.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".d") }.forEach { file ->
                readText(file)?.let { text -> DepFile.parse(text).forEach { out.add(it) } }
            }
        }
        return out
    }

    fun modified(path: Path): Long? = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull()

    private fun readText(path: Path): String? = runCatching { String(Files.readAllBytes(path)) }.getOrNull()
}
