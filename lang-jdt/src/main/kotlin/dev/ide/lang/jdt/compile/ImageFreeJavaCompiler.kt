package dev.ide.lang.jdt.compile

import org.eclipse.jdt.internal.compiler.Compiler
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies
import org.eclipse.jdt.internal.compiler.ICompilerRequestor
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader
import org.eclipse.jdt.internal.compiler.env.INameEnvironment
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Compiles Java sources to `.class` with ecj's **internal** compiler over a **classic** (non-module-aware)
 * [INameEnvironment]. Used for the Android/ART platform at compliance >= 9, where the batch front-end can't be:
 * it would put `android.jar` on `-bootclasspath` (rejected at >= 9), and a non-modular jar is not a valid
 * `--system` (and ART has no jimage). A classic name environment keeps ecj in **non-modular** mode at any
 * compliance, so `java.lang.Object` and friends bind straight from `android.jar`'s bytes — no JRT image and no
 * source-level cap. The platform's Java 9+ desugar stubs (`StringConcatFactory`/`LambdaMetafactory`) must be on
 * [libraries] (string concatenation/lambdas compile to an `invokedynamic` against them); D8 desugars the indy.
 */
internal object ImageFreeJavaCompiler {

    /** [libraries] = the platform (android.jar + desugar stubs) followed by the module's library jars. */
    fun compile(sources: List<Path>, libraries: List<Path>, outputDir: Path, sourceLevel: String): JdtBatchCompiler.Result {
        val zips = libraries.filter { Files.isRegularFile(it) }.mapNotNull { runCatching { ZipFile(it.toFile()) }.getOrNull() }
        try {
            // Package set for isPackage(), built once (vs. re-scanning per query). Class lookup is O(1) via getEntry.
            // ecj queries packages hierarchically (isPackage([], "java"), then (["java"], "lang"), …), so EVERY
            // prefix must be present — "java/lang/Object.class" contributes both "java" and "java/lang".
            val packages = HashSet<String>()
            for (z in zips) {
                val e = z.entries()
                while (e.hasMoreElements()) {
                    val n = e.nextElement().name
                    val slash = n.lastIndexOf('/')
                    if (slash <= 0 || !n.endsWith(".class")) continue
                    val pkg = n.substring(0, slash)
                    var i = pkg.indexOf('/')
                    while (i >= 0) { packages.add(pkg.substring(0, i)); i = pkg.indexOf('/', i + 1) }
                    packages.add(pkg)
                }
            }

            val env = object : INameEnvironment {
                override fun findType(compoundTypeName: Array<CharArray>): NameEnvironmentAnswer? = find(join(compoundTypeName))
                override fun findType(typeName: CharArray, packageName: Array<CharArray>): NameEnvironmentAnswer? =
                    find(if (packageName.isEmpty()) String(typeName) else join(packageName) + "/" + String(typeName))
                override fun isPackage(parentPackageName: Array<CharArray>?, packageName: CharArray): Boolean {
                    val parent = parentPackageName ?: emptyArray()
                    val pkg = if (parent.isEmpty()) String(packageName) else join(parent) + "/" + String(packageName)
                    return packages.contains(pkg)
                }
                override fun cleanup() {}

                private fun find(binarySlash: String): NameEnvironmentAnswer? {
                    val entryName = "$binarySlash.class"
                    for (z in zips) {
                        val e = z.getEntry(entryName) ?: continue
                        val bytes = runCatching { z.getInputStream(e).use { it.readBytes() } }.getOrNull() ?: continue
                        val reader = runCatching { ClassFileReader.read(bytes, entryName) }.getOrNull() ?: continue
                        return NameEnvironmentAnswer(reader, null)
                    }
                    return null
                }
                private fun join(parts: Array<CharArray>) = parts.joinToString("/") { String(it) }
            }

            val level = jdkLevel(sourceLevel)
            val options = CompilerOptions().apply {
                complianceLevel = level
                this.sourceLevel = level   // qualify: the `sourceLevel` param (String) shadows the field
                targetJDK = level
                // mirror the batch path's `-g`
                produceDebugAttributes = ClassFileConstants.ATTR_SOURCE or ClassFileConstants.ATTR_LINES or ClassFileConstants.ATTR_VARS
            }

            val messages = ArrayList<String>()
            var hadError = false
            val requestor = ICompilerRequestor { result ->
                result.allProblems?.forEach { p ->
                    if (p.isError) { hadError = true; messages.add("${String(p.originatingFileName)}: ${p.message}") }
                }
                // ecj skips class files for units with errors; only write a clean unit's output.
                if (!result.hasErrors()) {
                    for (cf in result.classFiles) {
                        val dst = outputDir.resolve(String(cf.fileName()) + ".class")
                        runCatching {
                            Files.createDirectories(dst.parent)
                            Files.write(dst, cf.bytes)
                        }.onFailure { hadError = true; messages.add("write failed $dst: ${it.message}") }
                    }
                }
            }
            val compiler = Compiler(
                env,
                DefaultErrorHandlingPolicies.proceedWithAllProblems(),
                options,
                requestor,
                DefaultProblemFactory(Locale.getDefault()),
            )
            val units = sources.filter { Files.isRegularFile(it) }.map { p ->
                // batch.CompilationUnit.getPackageName() is null → ecj reads the package from the source itself,
                // so the file's on-disk location is irrelevant. Read device-safely (no Files.readString: API 33+).
                CompilationUnit(String(Files.readAllBytes(p), Charsets.UTF_8).toCharArray(), p.toString(), "UTF-8")
            }
            return try {
                compiler.compile(units.toTypedArray())
                JdtBatchCompiler.Result(!hadError, messages)
            } catch (t: Throwable) {
                JdtBatchCompiler.Result(false, messages + "internal compile failed: ${t.message}")
            }
        } finally {
            zips.forEach { runCatching { it.close() } }
        }
    }

    private fun jdkLevel(level: String): Long = when (level.removePrefix("1.").takeWhile { it.isDigit() }.toIntOrNull() ?: 17) {
        8 -> ClassFileConstants.JDK1_8
        9 -> ClassFileConstants.JDK9
        11 -> ClassFileConstants.JDK11
        17 -> ClassFileConstants.JDK17
        21 -> ClassFileConstants.JDK21
        else -> ClassFileConstants.JDK17
    }
}
