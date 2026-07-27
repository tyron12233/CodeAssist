package dev.ide.decompiler

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Reads a library class's ATTACHED source — the whole file text — given its FQN, from `-sources.jar`s, the
 * JDK `src.zip`, Android `sources/`, and any source directories. The FQN → entry mapping tries each package
 * prefix from longest to shortest (`com/foo/Bar/Inner.kt` → `com/foo/Bar.kt`), so a nested class resolves to
 * its top-level file, and handles the JDK `src.zip` module prefix (`java.base/java/util/List.java`). Kotlin
 * `.kt` is preferred over `.java`.
 *
 * A facade whose `@JvmName` differs from its source file name (e.g. `CollectionsKt` ← `Collections.kt`) won't
 * resolve here — the caller then falls back to a decompiled stub.
 */
class LibrarySources(private val sourceJars: List<Path>, private val sourceDirs: List<Path>) {

    /** `(fileName, whole source text)` for [fqn], or null when no attached source contains it. */
    fun read(fqn: String): Pair<String, String>? {
        val top = fqn.substringBefore('<').substringBefore('$')
        val segs = top.split('.').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return null
        for (ext in EXTS) {
            for (n in segs.size downTo 1) {
                val rel = segs.take(n).joinToString("/") + ".$ext"
                val name = segs[n - 1] + ".$ext"
                for (dir in sourceDirs) {
                    val f = dir.resolve(rel)
                    if (Files.isRegularFile(f)) return name to runCatching { Files.readString(f) }.getOrElse { return null }
                }
                for (jar in sourceJars) {
                    readFromJar(jar, rel)?.let { return name to it }
                }
            }
        }
        return null
    }

    private fun readFromJar(jar: Path, rel: String): String? {
        if (!Files.isRegularFile(jar)) return null
        return runCatching {
            ZipFile(jar.toFile()).use { zf ->
                // Exact entry, or the module-prefixed form the JDK `src.zip` uses (`java.base/<rel>`).
                val entry = zf.getEntry(rel)
                    ?: zf.entries().asSequence().firstOrNull { !it.isDirectory && it.name.endsWith("/$rel") }
                entry?.let { zf.getInputStream(it).use { s -> String(s.readBytes(), Charsets.UTF_8) } }
            }
        }.getOrNull()
    }

    private companion object {
        val EXTS = listOf("kt", "java")
    }
}
