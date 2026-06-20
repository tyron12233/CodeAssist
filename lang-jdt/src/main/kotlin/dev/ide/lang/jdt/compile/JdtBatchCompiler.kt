package dev.ide.lang.jdt.compile

import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compiles Java sources to `.class` with the Eclipse batch compiler (ecj) — the JDT compile backend.
 * No reflection, no hosting `javac`. The signature is JDT-free (paths + a plain result) so callers (the
 * build engine, tests) don't depend on JDT internals.
 *
 * **Bootclasspath.** ecj normally borrows the running VM's platform classes (`rt.jar` on Java 8, the
 * `jrt:` jimage on Java 9+) as the implicit JRE library, so on a desktop JDK only [classpath] (module
 * outputs + library jars) need be supplied. **On ART (Dalvik) there is no such ecj-readable image** —
 * the platform lives in the boot `.oat`/`.art`/dex, which ecj cannot load — so with no explicit
 * `-bootclasspath` ecj finds *no* platform types and fails with `"The type java.lang.Object cannot be
 * resolved. It is indirectly referenced from required .class files."` For Android compilation the boot
 * library should be `android.jar` anyway (the user's code targets the SDK, not the host JDK), so callers
 * pass it via [bootClasspath]; when they don't, we fall back to the [classpath] on ART (it carries
 * `android.jar`, which holds `java.lang.Object`). Passing `-bootclasspath` also stops ecj from probing
 * the host VM's (absent) jimage. Desktop behaviour is unchanged — there `bootClasspath` is empty and the
 * runtime is not Dalvik, so ecj keeps using the host JDK's platform classes.
 */
object JdtBatchCompiler {

    data class Result(val success: Boolean, val messages: List<String>)

    /** Compliance level parsed from an ecj `-source`/`-target` string (`"8"`, `"11"`, `"1.8"`, …) is >= 9. */
    private fun complianceAtLeast9(level: String): Boolean {
        val n = level.removePrefix("1.").takeWhile { it.isDigit() }.toIntOrNull() ?: return false
        return n >= 9
    }

    /** True on Android's runtime (ART/Dalvik), where ecj cannot read the platform library off the VM. */
    private val isAndroidRuntime: Boolean =
        System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true) ||
            System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true)

    fun compile(
        sources: List<Path>,
        classpath: List<Path>,
        outputDir: Path,
        sourceLevel: String = "17",
        bootClasspath: List<Path> = emptyList(),
    ): Result {
        runCatching { Files.createDirectories(outputDir) }
        if (sources.isEmpty()) return Result(true, emptyList())

        // Explicit boot library if given; otherwise the compile classpath on ART (carries android.jar),
        // since the running VM exposes no platform classes ecj can read. Empty on desktop → host JDK.
        val boot = bootClasspath.ifEmpty { if (isAndroidRuntime) classpath else emptyList() }

        // When a boot library is the platform (android.jar, the ART path) at compliance >= 9, the batch
        // front-end is unusable: it would put android.jar on `-bootclasspath`, which ecj rejects at >= 9, and a
        // non-modular jar isn't a valid `--system` (and ART has no jimage). Compile instead via the internal ecj
        // compiler over a CLASSIC (non-module-aware) name environment, which keeps ecj in non-modular mode at any
        // compliance and resolves `java.*` straight from android.jar's bytes — no JRT image, no level cap. The
        // desugar stubs (`StringConcatFactory`, …) must be on [bootClasspath]/[classpath] for Java 9+ concat;
        // D8 desugars the resulting invokedynamic. See [ImageFreeJavaCompiler].
        if (boot.isNotEmpty() && complianceAtLeast9(sourceLevel)) {
            return ImageFreeJavaCompiler.compile(sources, boot + classpath, outputDir, sourceLevel)
        }

        val args = ArrayList<String>()
        args += listOf("-source", sourceLevel, "-target", sourceLevel, "-proc:none", "-nowarn", "-g")
        args += listOf("-d", outputDir.toString())
        if (boot.isNotEmpty()) {
            args += "-bootclasspath"
            args += boot.joinToString(File.pathSeparator) { it.toString() }
        }
        if (classpath.isNotEmpty()) {
            args += "-classpath"
            args += classpath.joinToString(File.pathSeparator) { it.toString() }
        }
        sources.forEach { args += it.toString() }

        val out = StringWriter()
        val err = StringWriter()
        val ok = runCatching {
            BatchCompiler.compile(args.toTypedArray(), PrintWriter(out), PrintWriter(err), null)
        }.getOrDefault(false)
        val messages = (err.toString() + "\n" + out.toString()).lines().filter { it.isNotBlank() }
        return Result(ok, messages)
    }
}
