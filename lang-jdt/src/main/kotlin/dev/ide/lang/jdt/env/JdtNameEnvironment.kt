package dev.ide.lang.jdt.env

import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit
import org.eclipse.jdt.internal.compiler.env.INameEnvironment
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer
import java.nio.file.Files
import java.nio.file.Path

/**
 * A custom Eclipse-JDT [INameEnvironment] backed entirely by the **project model + in-memory editor
 * buffers**, never the host process.
 *
 * `findType` resolves a type from, in order:
 *  1. the **in-memory overlay** (live editor working copies) — as JDT source ([ICompilationUnit]);
 *  2. the project's **source roots** on disk — as source;
 *  3. **library jars** and the JDK's **jrt image** — as binary ([ClassFileReader] over `.class` bytes).
 *
 * This is the on-device-correct foundation: completion/resolution can see unsaved edits to any file
 * (no disk flush / no shadow-file hack), and the platform classes come from the model's SDK as
 * *bytecode* (no reflection, no IDE app classpath leakage). It pairs with the low-level ecj compiler
 * (`org.eclipse.jdt.internal.compiler.Compiler.resolve(...)`), which takes an `INameEnvironment` —
 * unlike the public DOM `ASTParser`, whose environment is disk-only.
 *
 * The expensive, keystroke-invariant state — the jrt class index, the open jar handles, the platform +
 * library + source package set — lives in a shared [JdtEnvironmentCache] so it is built once per analyzer
 * rather than rebuilt on every resolve. Only the live editor [overlay] (and the packages it contributes)
 * is per-instance. The convenience public constructor builds a private, unshared cache for callers that
 * just need a one-off environment (e.g. tests); the resolver shares one cache across every keystroke.
 *
 * @param sourceRoots project source roots (this module + its dependencies), from the project model.
 * @param overlay live editor buffers keyed by fully-qualified class name ("com.example.Foo").
 * @param excludedTypes FQCNs the environment must NOT resolve (e.g. the focal unit being compiled, to
 *   avoid a duplicate type).
 */
class JdtNameEnvironment internal constructor(
    private val cache: JdtEnvironmentCache,
    private val sourceRoots: List<Path>,
    private val overlay: Map<String, CharArray>,
    private val excludedTypes: Set<String>,
    private val ownsCache: Boolean,
) : INameEnvironment {

    /**
     * Convenience constructor: builds a fresh, unshared [JdtEnvironmentCache] for this environment alone.
     * Correct but not reused — for one-off uses. The completion resolver uses the internal cache-sharing
     * constructor so the platform scan is paid once across all keystrokes.
     */
    constructor(
        sourceRoots: List<Path>,
        overlay: Map<String, CharArray>,
        classpathJars: List<Path>,
        jdkHome: Path?,
        excludedTypes: Set<String> = emptySet(),
    ) : this(JdtEnvironmentCache(sourceRoots, classpathJars, jdkHome), sourceRoots, overlay, excludedTypes, ownsCache = true)

    /** Packages contributed by the live editor buffers — small, and genuinely per-instance. */
    private val overlayPackages: Set<String> by lazy {
        val out = HashSet<String>()
        overlay.keys.forEach { JdtEnvironmentCache.addPackagePrefixes(it, out) }
        out
    }

    // --- INameEnvironment ---

    override fun findType(compoundTypeName: Array<CharArray>): NameEnvironmentAnswer? =
        resolve(compoundTypeName.joinToString(".") { String(it) })

    override fun findType(typeName: CharArray, packageName: Array<CharArray>): NameEnvironmentAnswer? {
        val pkg = packageName.joinToString(".") { String(it) }
        val fqcn = if (pkg.isEmpty()) String(typeName) else "$pkg.${String(typeName)}"
        return resolve(fqcn)
    }

    override fun isPackage(parentPackageName: Array<CharArray>?, packageName: CharArray): Boolean {
        val parent = parentPackageName?.joinToString(".") { String(it) } ?: ""
        val pkg = if (parent.isEmpty()) String(packageName) else "$parent.${String(packageName)}"
        return cache.isStaticPackage(pkg) || pkg in overlayPackages
    }

    /**
     * ecj calls cleanup() between resolution phases. The library jars are owned by the shared cache and
     * stay open across the whole completion (and beyond), so a shared environment releases nothing here —
     * that is what removes the per-resolve open/close churn. A privately-owned cache (the convenience
     * constructor) is closed so its handles do not leak.
     */
    override fun cleanup() {
        if (ownsCache) cache.close()
    }

    // --- resolution ---

    private fun resolve(fqcn: String): NameEnvironmentAnswer? {
        if (fqcn in excludedTypes) return null
        overlay[fqcn]?.let { return sourceAnswer(fqcn, it) }
        sourceOnDisk(fqcn)?.let { return sourceAnswer(fqcn, it) }
        binaryBytes(fqcn)?.let { return binaryAnswer(fqcn, it) }
        return null
    }

    private fun sourceAnswer(fqcn: String, contents: CharArray): NameEnvironmentAnswer =
        NameEnvironmentAnswer(JdtSourceUnit(fqcn, contents), null)

    private fun binaryAnswer(fqcn: String, bytes: ByteArray): NameEnvironmentAnswer? =
        runCatching {
            NameEnvironmentAnswer(ClassFileReader.read(bytes, fqcn.replace('.', '/') + ".class"), null)
        }.getOrNull()

    private fun sourceOnDisk(fqcn: String): CharArray? {
        val rel = fqcn.replace('.', '/') + ".java"
        for (root in sourceRoots) {
            val p = root.resolve(rel)
            if (Files.isRegularFile(p)) return runCatching { Files.readString(p).toCharArray() }.getOrNull()
        }
        return null
    }

    private fun binaryBytes(fqcn: String): ByteArray? {
        val classPath = fqcn.replace('.', '/') + ".class"
        for (i in 0 until cache.jarCount) {
            val z = cache.zipAt(i) ?: continue
            val bytes = runCatching { z.getEntry(classPath)?.let { e -> z.getInputStream(e).use { it.readBytes() } } }
                .getOrElse {
                    // defensive: a transiently-bad handle — reopen the jar once and retry
                    val z2 = cache.reopenZip(i) ?: return@getOrElse null
                    runCatching { z2.getEntry(classPath)?.let { e -> z2.getInputStream(e).use { it.readBytes() } } }.getOrNull()
                }
            if (bytes != null) return bytes
        }
        return cache.jrtBytes(fqcn)
    }
}
