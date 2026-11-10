package dev.ide.spi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shipping app's R8 rules keep the name of every class a plugin can link against.
 *
 * R8 renames the app's own code (Google Play scores how much of it is renamed), but a plugin APK links the
 * host's copies of the published SPI BY NAME. A class in a package the rules don't keep would be renamed in
 * the release build alone: debug builds, desktop and every JVM test still pass, and installed plugins fail
 * with `NoClassDefFoundError` only on users' devices. So every package named in the `:spi-compat`
 * baselines (`<module>/api/<module>.api`) must be matched by a `-keep class <package>.*` (or a `.**` keep on
 * it or a parent) in `app/ide-android/proguard-rules.pro`.
 */
class ProguardSpiKeepsTest {

    @Test
    fun `every published SPI package keeps its names in the release build`() {
        val root = repoRoot()
        val baselines = (root.listFiles().orEmpty().toList() + root.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() })
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .map { File(it, "api/${it.name}.api") }
            .filter(File::isFile)
        assertTrue(baselines.isNotEmpty(), "found no SPI baselines under $root")

        val packages = baselines.flatMap { file ->
            file.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("\t") }
                .map { it.trim().substringBeforeLast('/').replace('/', '.') }
        }.toSortedSet()

        val rules = File(root, "app/ide-android/proguard-rules.pro").readLines()
        val keepPattern = Regex("""^-keep\s+class\s+([\w.$]+?)\.(\*\*?)\s*\{""")
        val exact = mutableSetOf<String>()
        val recursive = mutableSetOf<String>()
        for (line in rules) {
            val m = keepPattern.find(line.trim()) ?: continue
            if (m.groupValues[2] == "**") recursive += m.groupValues[1] else exact += m.groupValues[1]
        }

        val unkept = packages.filter { pkg ->
            pkg !in exact && recursive.none { pkg == it || pkg.startsWith("$it.") }
        }
        assertTrue(
            unkept.isEmpty(),
            "These published SPI packages are renamed by R8 in the release build, so plugins can't link them. " +
                "Add `-keep class <package>.* { *; }` to app/ide-android/proguard-rules.pro:\n" +
                unkept.joinToString("\n") { "  $it" },
        )
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("no settings.gradle.kts above ${System.getProperty("user.dir")}")
    }
}
