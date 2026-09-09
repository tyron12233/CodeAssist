package dev.ide.core

import dev.ide.lang.JvmIndexScopeProvider
import dev.ide.lang.jdt.SourceMethodResolver
import dev.ide.lang.resolve.SourceDocProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * The LIVE-parse [SourceDocProvider] for a module (real parameter names + javadoc parsed from `.java` source
 * on demand), backing [IndexBackedSourceDocs] wherever the persistent source-doc index has no answer.
 *
 * Two cases depend on it, not just the startup window. A PROJECT `.java` source is never in the source-doc
 * index at all (`java.sourceDoc` only accepts [dev.ide.index.IndexOrigin.LIBRARY_SOURCE]), so once a sibling
 * module's compiled output puts that class on the Kotlin module's classpath, its bytecode shape (`p0`/`p1`,
 * no doc) wins and this is the only thing that can name its parameters. And a freshly attached `-sources.jar`
 * answers here until its segment is built.
 *
 * The roots come from the backend-neutral [JvmIndexScopeProvider], never a concrete analyzer type: the `.java`
 * editor backend has already been swapped once (JDT → IntelliJ PSI), and a cast to the concrete type degrades
 * SILENTLY to "every Java parameter is `p0`" rather than failing loudly. They are also re-read per lookup
 * because the host attaches archives AFTER the analyzer is built (the SDK Manager's source download, the JDK
 * `src.zip`), and a resolver captured once would never see them.
 */
class AnalyzerSourceDocs(private val analyzer: JvmIndexScopeProvider) : SourceDocProvider {

    private var lastRoots: List<Path>? = null
    private var lastArchives: List<Path>? = null
    private var resolver: SourceMethodResolver? = null

    /**
     * The resolver over the analyzer's CURRENT roots, rebuilt only when they actually changed. A rebuild
     * discards the resolver's parse cache, so the list comparison (cheap: a handful of paths) is what keeps
     * repeated lookups from re-parsing. The `isDirectory` split is only paid on an actual change.
     */
    private fun resolver(): SourceMethodResolver {
        val roots = analyzer.sourceRootPaths
        val archives = analyzer.librarySourceArchives
        resolver?.let { if (roots == lastRoots && archives == lastArchives) return it }
        val (dirs, jars) = archives.partition { Files.isDirectory(it) }
        return SourceMethodResolver(roots + dirs, jars).also {
            resolver = it
            lastRoots = roots
            lastArchives = archives
        }
    }

    // [SourceMethodResolver]'s parse caches are plain HashMaps, and this provider is now reached from both the
    // Java and the Kotlin backend (each on its own dispatcher), so every entry point is guarded. Contention is
    // not a concern: this is the index-miss path, and a hit is a map lookup.
    @Synchronized
    override fun method(declaringFqn: String, methodName: String, arity: Int): SourceDocProvider.MethodDoc? =
        resolver().method(declaringFqn, methodName, arity)

    @Synchronized
    override fun classDoc(fqn: String): String? = resolver().classDoc(fqn)

    @Synchronized
    override fun methodRaw(declaringFqn: String, methodName: String, arity: Int): String? =
        resolver().methodRaw(declaringFqn, methodName, arity)

    @Synchronized
    override fun classDocRaw(fqn: String): String? = resolver().classDocRaw(fqn)
}
