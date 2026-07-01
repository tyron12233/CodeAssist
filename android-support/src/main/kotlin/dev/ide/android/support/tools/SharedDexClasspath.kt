package dev.ide.android.support.tools

import com.android.tools.r8.ArchiveClassFileProvider
import com.android.tools.r8.ClassFileResourceProvider
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.ProgramResource
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Process-wide cache of D8 [ClassFileResourceProvider]s for the library (bootclasspath) and desugaring-classpath
 * jars, mirroring AGP's `ClassFileProviderFactory` registered in the dex task's `sharedState`.
 *
 * Building a provider parses the jar's central directory and indexes its class descriptors ONCE; reusing the
 * same instance across D8 invocations means `android.jar` (~26 MB, thousands of classes) and every stable
 * library jar are opened and indexed a single time instead of re-parsed for every one of the dozens of library
 * archives a build dexes — the dominant cost of a cold `dexBuilder` (previously O(libraries²) jar opens, since
 * with desugaring each library also gets every other library as its classpath). D8 reads individual class bytes
 * lazily through the provider, so sharing costs only the retained descriptor index, not the class data.
 *
 * Keyed by path + size + mtime, so a rebuilt jar (rare) re-indexes rather than serving stale bytes. Bounded LRU;
 * an evicted provider is closed (its archive released). Thread-safe: providers are shared across the parallel
 * dex workers, and both `ArchiveClassFileProvider` (a [java.util.zip.ZipFile] under the hood) and the retained
 * descriptor set are safe for concurrent reads.
 */
internal object SharedDexClasspath {
    /** Far above any single build's jar count (so no eviction mid-build → no close of an in-use provider), but
     *  bounded so a long multi-project session doesn't accumulate open archive handles without limit. */
    private const val MAX_ENTRIES = 384

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, ClassFileResourceProvider>(64, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClassFileResourceProvider>): Boolean {
            if (size <= MAX_ENTRIES) return false
            (eldest.value as? Closeable)?.let { runCatching { it.close() } }
            return true
        }
    }

    /**
     * A shared provider for [jar], or null when it isn't an openable archive (a directory, missing, or a
     * zero-entry/corrupt zip) — the caller then falls back to D8's `addLibraryFiles`/`addClasspathFiles`.
     */
    fun provider(jar: Path): ClassFileResourceProvider? {
        if (!Files.isRegularFile(jar)) return null
        val key = runCatching {
            val a = Files.readAttributes(jar, BasicFileAttributes::class.java)
            "$jar|${a.size()}|${a.lastModifiedTime().toMillis()}"
        }.getOrElse { jar.toString() }
        synchronized(lock) { cache[key]?.let { return it } }
        // Build outside the lock — indexing android.jar is slow, and holding the lock would serialize the
        // parallel dex workers on the first (cold) lookup. A lost race just builds a duplicate that we discard.
        val built = runCatching { NonClosingArchiveProvider(ArchiveClassFileProvider(jar)) }.getOrNull() ?: return null
        synchronized(lock) {
            cache[key]?.let { runCatching { built.close() }; return it }
            cache[key] = built
            return built
        }
    }
}

/**
 * Wraps an [ArchiveClassFileProvider] so D8's per-compilation `finished()` callback does NOT close the shared
 * archive — `ArchiveClassFileProvider.finished()` closes its `ZipFile`, which would break the next invocation
 * that reuses this provider. [SharedDexClasspath] owns the lifecycle and closes it on cache eviction instead.
 */
private class NonClosingArchiveProvider(private val delegate: ArchiveClassFileProvider) : ClassFileResourceProvider, Closeable {
    override fun getClassDescriptors(): MutableSet<String> = delegate.classDescriptors
    override fun getProgramResource(descriptor: String): ProgramResource? = delegate.getProgramResource(descriptor)
    override fun finished(handler: DiagnosticsHandler) { /* shared across invocations: closing is the cache's job */ }
    override fun close() = delegate.close()
}
