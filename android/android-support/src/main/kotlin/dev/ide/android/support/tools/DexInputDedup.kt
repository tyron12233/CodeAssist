package dev.ide.android.support.tools

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Makes a TOOL classpath (a KSP processor closure, a kotlinc compiler-plugin closure) dexable by resolving
 * duplicate classes the way the JVM already does, instead of failing.
 *
 * A `URLClassLoader` tolerates the same class appearing in several jars: the first jar on the classpath wins
 * and the rest are shadowed. **Dex has no such rule**: D8 rejects the whole input with
 * `Duplicate class '<name>'`. So a classpath the desktop loaders run happily can be undexable on device, which
 * is exactly what happens as soon as a module activates two bundled KSP processors: each processor's closure
 * ships its own copy of the shared transitive libraries (all four bundles carry `annotations-13.0.jar`; three
 * carry `jsr305`; Room/Moshi/Hilt/Glide each carry their own `guava`, `kotlin-reflect` and `kotlinpoet`), and
 * their union defines thousands of classes twice.
 *
 * [firstWins] applies the `URLClassLoader` precedence explicitly: walk the classpath in order and keep a class
 * only the first time it is seen. The classpath order is therefore the version-selection policy on device and
 * on desktop alike, so neither platform can pick a different copy than the other.
 *
 * Only `.class` entries are considered. Resources are irrelevant here: D8 reads classes and nothing else, and
 * the callers that need a jar's resources (a processor's `META-INF/services` descriptor) read them from the
 * ORIGINAL jars, never from the dex inputs.
 */
object DexInputDedup {

    /**
     * A classpath equivalent to [jars] with no class defined twice, first occurrence winning.
     *
     * A jar whose classes are all new is passed through as its ORIGINAL path (no copy, the common case, and
     * what keeps a single-processor classpath free). A jar every one of whose classes is already defined is
     * dropped outright. Only a PARTIAL overlap (two versions of one library) needs a rewrite, into [outDir].
     */
    fun firstWins(jars: List<Path>, outDir: Path): List<Path> {
        val seen = HashSet<String>()
        val out = ArrayList<Path>(jars.size)
        for ((index, jar) in jars.withIndex()) {
            val classes = classEntriesOf(jar)
            if (classes.isEmpty()) {           // nothing to clash over (a resources-only jar): keep verbatim
                out.add(jar)
                continue
            }
            val duplicates = classes.filterTo(HashSet()) { it in seen }
            seen.addAll(classes)
            when {
                duplicates.isEmpty() -> out.add(jar)
                duplicates.size == classes.size -> Unit  // fully shadowed: contributes nothing, drop it
                // A name-mangled destination: two bundles can hold same-NAMED jars (`annotations-13.0.jar`),
                // and writing both to `outDir/<name>` would have one silently overwrite the other.
                else -> out.add(rewriteWithout(jar, duplicates, outDir.resolve("$index-${jar.fileName}")))
            }
        }
        return out
    }

    /** The `.class` entry names declared by [jar]; empty when it has none (or can't be read as a zip). */
    private fun classEntriesOf(jar: Path): Set<String> = runCatching {
        ZipFile(jar.toFile()).use { zf ->
            zf.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .mapTo(HashSet()) { it.name }
        }
    }.getOrDefault(emptySet())

    /** Stream [jar] into [dest], omitting the [drop] entries; everything else is copied byte-for-byte. */
    private fun rewriteWithout(jar: Path, drop: Set<String>, dest: Path): Path {
        Files.createDirectories(dest.parent)
        ZipInputStream(BufferedInputStream(Files.newInputStream(jar))).use { zis ->
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(dest))).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name !in drop) {
                        val content = zis.readBytes()
                        zos.putNextEntry(ZipEntry(entry.name)) // fresh entry: CRC/size/compression recomputed
                        zos.write(content)
                        zos.closeEntry()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return dest
    }
}
