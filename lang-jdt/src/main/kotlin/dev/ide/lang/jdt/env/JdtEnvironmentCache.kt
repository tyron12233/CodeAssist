package dev.ide.lang.jdt.env

import dev.ide.index.IndexId
import dev.ide.index.IndexService
import dev.ide.lang.jdt.index.normalizedJarKey
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
 *    is immutable, so it lives in a process-global [JrtImage] keyed by home, shared by every module/project
 *    on the same JDK (one jrt walk, one 40k-entry map for the whole process), not rebuilt per analyzer;
 *  - the **classpath** (library-jar handles + the packages contributed by the module's jars and source
 *    roots) is genuinely per-module, so it lives here, on the per-analyzer cache.
 *
 * **Index-backed library access.** Library `.class` bytes are served by [libraryBytes]. When the workspace
 * index is `ready` it locates a type's owning jar via the `java.classLocator` index (filtered to THIS
 * module's classpath) and opens exactly that one jar; an empty result is an authoritative "not on this
 * classpath" (no probing). When the index is not ready (cold first build, or mid-rebuild after a model
 * change) it falls back to probing every classpath jar, the always-correct path, so resolution never
 * depends on the index being up. Because a ready index gives authoritative negatives, library jars no
 * longer need to be held open: they go through a small **LRU handle pool** ([MAX_OPEN_JARS]) instead of one
 * permanently-open handle per jar, which on a large (e.g. Compose) classpath is the difference between a few
 * open descriptors and several hundred. The pool is unbounded until the index is ready (so the probe path
 * does not thrash), then trims to the cap.
 *
 * Lifetime: one cache per analyzer (held by `JdtResolver`), recreated on any model change (so the classpath
 * is fixed for the cache's life). The cache is a [Disposable]; [dispose] closes the pooled handles. The
 * shared [JrtImage] is process-lived and immutable and is never closed here.
 */
internal class JdtEnvironmentCache(
    private val sourceRoots: List<Path>,
    private val classpathJars: List<Path>,
    jdkHome: Path?,
    /** The workspace index, for the `java.classLocator` fast path. Null (the default) ⇒ always probe. Read
     *  through a supplier because the host sets the analyzer's index AFTER the analyzer is constructed. */
    private val indexProvider: () -> IndexService? = { null },
) : Disposable {

    /** The shared platform image for this analyzer's SDK (null if no JDK home is configured). */
    private val jrt: JrtImage? = jdkHome?.let { JrtImage.forHome(it) }

    /** Platform classes (java.*, …) by FQCN → the `.class` Path in the jrt image. Shared, built once. */
    val jrtIndex: Map<String, Path> get() = jrt?.index ?: emptyMap()

    /** Packages contributed by this module's library jars + source roots (the platform's live in [jrt]). */
    private val modulePackages: Set<String> by lazy { buildModulePackages() }

    /** This module's classpath jars by their normalized key, so a locator hit can be confined to them. */
    private val classpathByKey: Map<String, Path> = classpathJars.associateBy { normalizedJarKey(it) }

    /** LRU pool of open jar handles. Access-order so the eldest entry is the least-recently-used; trimmed
     *  to the cap explicitly in [trimToCap] (the cap is dynamic, so a self-evicting map can't express it). */
    private val pool = LinkedHashMap<Path, ZipFile>(16, 0.75f, true)

    /** fqcn → its owning classpath jar (or [NOT_FOUND] for an authoritative miss). Bounded; cleared on a
     *  ready transition so a negative cached during a stale window self-heals. */
    private val locateCache = object : LinkedHashMap<String, Path>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Path>) = size > LOCATE_CACHE_MAX
    }
    private var lastReady = false

    fun jrtBytes(fqcn: String): ByteArray? = jrt?.bytes(fqcn)

    /** Currently-open pooled jar handles (for tests asserting the LRU cap). */
    @Synchronized
    fun openHandleCount(): Int = pool.size

    fun isStaticPackage(pkg: String): Boolean = jrt?.isPackage(pkg) == true || pkg in modulePackages

    /** The library bytecode for [fqcn], or null if no classpath jar (or the jrt) defines it. */
    fun libraryBytes(fqcn: String): ByteArray? {
        val classPath = fqcn.replace('.', '/') + ".class"
        if (!indexReady()) return readEntryProbing(classPath)
        // Authoritative: the locator (filtered to this classpath) either names the one owning jar, or the
        // type is not on this classpath. No probing, that is what lets the pool stay small. The locator keys
        // TOP-LEVEL types only (classEntryToFqn skips '$'), but ecj's name environment also requests NESTED
        // types by binary name (e.g. android.view.View$OnClickListener / Window$Callback, interfaces in the
        // Activity hierarchy). A nested type lives in its enclosing top-level type's jar, so on a '$' name fall
        // back to locating that enclosing type and read the nested .class from the same jar.
        val owner = locate(fqcn) ?: fqcn.indexOf('$').takeIf { it > 0 }?.let { locate(fqcn.substring(0, it)) }
        return owner?.let { readEntry(it, classPath) }
    }

    private fun indexReady(): Boolean = runCatching { indexProvider()?.status?.ready == true }.getOrDefault(false)

    /** The classpath jar owning [fqcn] per the `java.classLocator` index, or null if none on this classpath. */
    private fun locate(fqcn: String): Path? {
        if (indexReady() != lastReady) synchronized(this) { locateCache.clear(); lastReady = indexReady() }
        synchronized(this) { locateCache[fqcn]?.let { return if (it === NOT_FOUND) null else it } }
        val index = indexProvider() ?: return null
        // The index is workspace-wide (a superset of this module's classpath), so keep only a hit that is
        // actually on this module's classpath, that preserves per-module classpath isolation.
        val jar = runCatching { index.exact<String>(LOCATOR_ID, fqcn).firstNotNullOfOrNull { classpathByKey[it] } }.getOrNull()
        synchronized(this) { locateCache[fqcn] = jar ?: NOT_FOUND }
        return jar
    }

    /** Read [entryName] from [jar] through the pool, opening it (LRU) if needed. Synchronized so a handle is
     *  never closed by an eviction on another thread while this read is in flight (reads complete in-lock). */
    @Synchronized
    fun readEntry(jar: Path, entryName: String): ByteArray? {
        val zip = zipFor(jar) ?: return null
        val bytes = runCatching { zip.getEntry(entryName)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } }
            .getOrElse {
                // Defensive: a transiently-bad handle, reopen the jar once and retry.
                val z2 = reopen(jar) ?: return null
                runCatching { z2.getEntry(entryName)?.let { e -> z2.getInputStream(e).use { it.readBytes() } } }.getOrNull()
            }
        return bytes
    }

    /** Fallback when the index can't locate: read [entryName] from the first classpath jar that has it. */
    @Synchronized
    fun readEntryProbing(entryName: String): ByteArray? {
        for (jar in classpathJars) readEntry(jar, entryName)?.let { return it }
        return null
    }

    @Synchronized
    private fun zipFor(jar: Path): ZipFile? {
        pool[jar]?.let { return it }
        val z = runCatching { ZipFile(jar.toFile()) }.getOrNull() ?: return null
        pool[jar] = z
        trimToCap()
        return z
    }

    private fun reopen(jar: Path): ZipFile? {
        pool.remove(jar)?.let { runCatching { it.close() } }
        return zipFor(jar)
    }

    /** Close least-recently-used handles down to the current cap (unbounded until the index is ready). */
    private fun trimToCap() {
        val cap = if (indexReady()) MAX_OPEN_JARS else Int.MAX_VALUE
        if (pool.size <= cap) return
        val it = pool.entries.iterator()
        while (pool.size > cap && it.hasNext()) {
            val e = it.next()
            runCatching { e.value.close() }
            it.remove()
        }
    }

    /** The `.class` entry names in [jar], through the pool (transient open; not held beyond the cap). */
    @Synchronized
    private fun enumerateClassEntries(jar: Path, into: (String) -> Unit) {
        val zip = zipFor(jar) ?: return
        runCatching {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name.endsWith(".class")) into(name)
            }
        }
    }

    /** Release the module's pooled jar handles. The shared [JrtImage] is process-lived and is not closed here. */
    @Synchronized
    override fun dispose() {
        for (z in pool.values) runCatching { z.close() }
        pool.clear()
    }

    /** Alias for [dispose], the resolver/tests call this directly. */
    fun close() = dispose()

    private fun buildModulePackages(): Set<String> {
        val out = HashSet<String>()
        for (jar in classpathJars) {
            enumerateClassEntries(jar) { name -> addPackagePrefixes(name.removeSuffix(".class").replace('/', '.'), out) }
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
        /** Cap on concurrently-open library jar handles once the index can serve authoritative locations. */
        private const val MAX_OPEN_JARS = 24
        private const val LOCATE_CACHE_MAX = 8192
        private val LOCATOR_ID = IndexId("java.classLocator")

        /** Sentinel for "checked the locator, not on this classpath" (so a negative is cached, not re-queried). */
        private val NOT_FOUND: Path = Paths.get("__codeassist_locator_miss__")

        /**
         * Add every dotted package prefix of [fqcn] to [out] (so `a.b.C` adds `a` and `a.b`). Walks the dots
         * directly instead of `split('.')` + per-prefix `joinToString`, the build visits tens of thousands
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
 * The immutable platform class index for one JDK home, built once and shared process-wide, keyed by the
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

        /** The shared image for [home], opened/built at most once per JDK across the whole process. */
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
