package dev.ide.lang.jdt.env

import dev.ide.platform.Disposable
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import java.nio.file.Paths

/**
 * The keystroke-invariant environment state for one analyzer, split by what each part actually depends on:
 *
 *  - the **platform** (the JDK jrt class index + the packages it defines) depends only on the SDK home and
 *    is immutable, so it lives in a process-global [JrtImage] keyed by home — shared by every module/project
 *    on the same JDK (one jrt walk, one 40k-entry map for the whole process), not rebuilt per analyzer;
 *  - the **classpath** (library-jar handles + the packages contributed by the module's jars and source
 *    roots) is genuinely per-module, so it lives here, on the per-analyzer cache.
 *
 * This is the fix for the dominant editor-time hotspot. A fresh [JdtNameEnvironment] previously rebuilt all
 * of it on every resolve — walking the whole jrt image into a 40k-entry map and re-enumerating every library
 * jar — and `complete()` resolves up to five marker-splice variants per keystroke. On ART, where
 * `android.jar` *is* a jar, that allocated tens of MB per keystroke and stalled the GC.
 *
 * Lifetime: one cache per analyzer (held by `JdtResolver`). The library jars are the module's own and change
 * only on a model rebuild (which makes a new analyzer), so the handles are opened once and kept open. The
 * cache is a [Disposable]: the host registers it with the platform disposer so [dispose] releases the jar
 * handles when the workspace closes. The shared [JrtImage] is process-lived and immutable — never closed
 * here (the JVM's own jrt filesystem must not be closed, and a project-supplied JDK's is reused across
 * modules). Only the small overlay-derived package set stays per-[JdtNameEnvironment].
 */
internal class JdtEnvironmentCache(
    private val sourceRoots: List<Path>,
    private val classpathJars: List<Path>,
    jdkHome: Path?,
) : Disposable {

    /** The shared platform image for this analyzer's SDK (null if no JDK home is configured). */
    private val jrt: JrtImage? = jdkHome?.let { JrtImage.forHome(it) }

    /** Platform classes (java.*, …) by FQCN → the `.class` Path in the jrt image. Shared, built once. */
    val jrtIndex: Map<String, Path> get() = jrt?.index ?: emptyMap()

    /** Packages contributed by this module's library jars + source roots (the platform's live in [jrt]). */
    private val modulePackages: Set<String> by lazy { buildModulePackages() }

    // Library jars opened lazily and kept open for the cache's life (no per-resolve open/close churn, no
    // central-directory re-parse). cleanup() on a shared env is a no-op; only dispose() releases them.
    private val openZips: Array<ZipFile?> = arrayOfNulls(classpathJars.size)

    val jarCount: Int get() = classpathJars.size

    @Synchronized
    fun zipAt(i: Int): ZipFile? {
        openZips[i]?.let { return it }
        return runCatching { ZipFile(classpathJars[i].toFile()) }.getOrNull()?.also { openZips[i] = it }
    }

    /** One-shot reopen after a read failure on jar [i] (defensive — handles a transiently-bad handle). */
    @Synchronized
    fun reopenZip(i: Int): ZipFile? {
        runCatching { openZips[i]?.close() }
        openZips[i] = null
        return zipAt(i)
    }

    /** Read a platform class's bytecode from the shared jrt image. */
    fun jrtBytes(fqcn: String): ByteArray? = jrt?.bytes(fqcn)

    fun isStaticPackage(pkg: String): Boolean = jrt?.isPackage(pkg) == true || pkg in modulePackages

    /** Release the module's jar handles. The shared [JrtImage] is process-lived and is not closed here. */
    @Synchronized
    override fun dispose() {
        for (i in openZips.indices) {
            runCatching { openZips[i]?.close() }
            openZips[i] = null
        }
    }

    /** Alias for [dispose] — the resolver/tests call this directly. */
    fun close() = dispose()

    private fun buildModulePackages(): Set<String> {
        val out = HashSet<String>()
        for (i in classpathJars.indices) {
            val zip = zipAt(i) ?: continue
            runCatching {
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (name.endsWith(".class")) addPackagePrefixes(name.removeSuffix(".class").replace('/', '.'), out)
                }
            }
        }
        for (root in sourceRoots) {
            if (!Files.isDirectory(root)) continue
            Files.walk(root).use { s ->
                s.filter { it.toString().endsWith(".java") }
                    .forEach { addPackagePrefixes(root.relativize(it).toString().removeSuffix(".java").replace('/', '.'), out) }
            }
        }
        return out
    }

    companion object {
        /**
         * Add every dotted package prefix of [fqcn] to [out] (so `a.b.C` adds `a` and `a.b`). Walks the dots
         * directly instead of `split('.')` + per-prefix `joinToString` — the build visits tens of thousands
         * of class names, so the per-class List/join allocation the naive form pays is worth avoiding.
         */
        fun addPackagePrefixes(fqcn: String, out: MutableSet<String>) {
            val pkgEnd = fqcn.lastIndexOf('.')
            if (pkgEnd <= 0) return // default package, or no package segment
            out.add(fqcn.substring(0, pkgEnd))
            var dot = fqcn.indexOf('.')
            while (dot in 1 until pkgEnd) {
                out.add(fqcn.substring(0, dot))
                dot = fqcn.indexOf('.', dot + 1)
            }
        }
    }
}

/**
 * The immutable platform class index for one JDK home — built once and shared process-wide, keyed by the
 * normalized home path. The jrt image content is a pure function of the SDK on disk, identical for every
 * module and project that targets it, so deduplicating it here turns N modules' N jrt walks (and N copies of
 * the ~40k-entry map) into one. The index and package set build lazily on first use.
 */
internal class JrtImage private constructor(private val fs: FileSystem?) {

    /** Platform class FQCN → its `.class` Path in the jrt image. */
    val index: Map<String, Path> by lazy { buildIndex() }

    /** Every dotted package prefix defined by the platform classes. */
    val packages: Set<String> by lazy {
        val out = HashSet<String>()
        index.keys.forEach { JdtEnvironmentCache.addPackagePrefixes(it, out) }
        out
    }

    fun isPackage(pkg: String): Boolean = pkg in packages

    fun bytes(fqcn: String): ByteArray? = index[fqcn]?.let { runCatching { Files.readAllBytes(it) }.getOrNull() }

    private fun buildIndex(): Map<String, Path> {
        val fs = fs ?: return emptyMap()
        val map = HashMap<String, Path>(40_000)
        val modules = fs.getPath("/modules")
        if (!Files.exists(modules)) return map
        Files.walk(modules).use { stream ->
            stream.filter { it.toString().endsWith(".class") }.forEach { p ->
                val rel = modules.relativize(p).toString().substringAfter('/') // strip <module>/
                if (rel.endsWith("module-info.class") || rel.endsWith("package-info.class")) return@forEach
                map.putIfAbsent(rel.removeSuffix(".class").replace('/', '.'), p)
            }
        }
        return map
    }

    companion object {
        private val images = ConcurrentHashMap<String, JrtImage>()

        /** The shared image for [home] — opened/built at most once per JDK across the whole process. */
        fun forHome(home: Path): JrtImage = images.computeIfAbsent(keyOf(home)) { JrtImage(openJrt(home)) }

        private fun keyOf(home: Path): String =
            runCatching { home.toAbsolutePath().normalize().toString() }.getOrDefault(home.toString())

        /**
         * Open the jrt image for [home]. The current JVM's jrt filesystem is a shared singleton that must
         * never be closed; a project-supplied JDK home gets a new filesystem, opened once per home thanks to
         * the [images] cache (so `newFileSystem` cannot collide with an already-open one).
         */
        private fun openJrt(home: Path): FileSystem? = runCatching {
            val current = Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize()
            if (home.toAbsolutePath().normalize() == current) FileSystems.getFileSystem(URI.create("jrt:/"))
            else FileSystems.newFileSystem(URI.create("jrt:/"), mapOf("java.home" to home.toString()))
        }.getOrElse { runCatching { FileSystems.getFileSystem(URI.create("jrt:/")) }.getOrNull() }
    }
}
