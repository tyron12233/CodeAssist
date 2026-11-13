package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The keep rules a library jar carries for whoever shrinks code that uses it (`META-INF/proguard/` and the
 * R8-targeted `META-INF/com.android.tools/r8…/`).
 *
 * R8 reads these on its own from every PROGRAM jar. A jar passed as classpath is not searched, and a build
 * that shrinks only the project's own code passes its libraries exactly that way; the rules still matter
 * there, since many of them are about the consumer's classes (`@Keep`, a `ViewModel`'s constructor, a
 * serializer's companion). So that path extracts them and hands them to R8 as ordinary rule files.
 */
internal object EmbeddedKeepRules {
    private const val LEGACY = "META-INF/proguard/"
    private const val TOOLS = "META-INF/com.android.tools/"

    /** The R8 line the bundled shrinker belongs to, for the version-ranged directories. */
    private val R8_VERSION = intArrayOf(8, 13, 0)

    /** Write every applicable rule file in [jars] under [outDir] and return them, in jar order. */
    fun extract(jars: List<Path>, outDir: Path): List<Path> {
        val out = ArrayList<Path>()
        for ((i, jar) in jars.withIndex()) {
            if (!Files.isRegularFile(jar) || Files.size(jar) == 0L) continue
            runCatching {
                ZipFile(jar.toFile()).use { zip ->
                    val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
                    for (name in applicable(names)) {
                        val target = outDir.resolve("$i-" + name.removePrefix("META-INF/").replace('/', '_'))
                        Files.createDirectories(outDir)
                        zip.getInputStream(zip.getEntry(name)).use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                        out.add(target)
                    }
                }
            }
        }
        return out
    }

    /**
     * Which of a jar's entries R8 would apply: the R8-targeted directories whose version range covers this R8
     * when the jar has any, else the legacy `META-INF/proguard/` files (a jar that ships R8-targeted rules means
     * for them to replace its legacy ones).
     */
    internal fun applicable(entryNames: List<String>): List<String> {
        val targeted = entryNames.filter { it.startsWith(TOOLS + "r8") }
        if (targeted.isEmpty()) return entryNames.filter { it.startsWith(LEGACY) }.sorted()
        return targeted.filter { covers(it.removePrefix(TOOLS).substringBefore('/')) }.sorted()
    }

    /** `r8`, `r8-from-1.6.0`, `r8-upto-8.2.0`, `r8-from-8.0.0-upto-8.2.0`: from is inclusive, upto exclusive. */
    private fun covers(dir: String): Boolean {
        val from = Regex("-from-([0-9.]+)").find(dir)?.groupValues?.get(1)
        val upto = Regex("-upto-([0-9.]+)").find(dir)?.groupValues?.get(1)
        if (from != null && compare(R8_VERSION, parse(from)) < 0) return false
        if (upto != null && compare(R8_VERSION, parse(upto)) >= 0) return false
        return true
    }

    private fun parse(v: String): IntArray = v.split('.').mapNotNull { it.toIntOrNull() }.toIntArray()

    private fun compare(a: IntArray, b: IntArray): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
