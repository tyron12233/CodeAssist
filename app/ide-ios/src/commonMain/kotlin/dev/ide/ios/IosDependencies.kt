package dev.ide.ios

import dev.ide.deps.ConflictPolicy
import dev.ide.deps.Repository
import dev.ide.deps.ResolutionResult
import dev.ide.deps.impl.ArtifactSearch
import dev.ide.deps.impl.ArtifactFetcher
import dev.ide.deps.impl.HttpArtifactFetcher
import dev.ide.deps.impl.MavenDependencyResolver
import dev.ide.deps.impl.ResolverCache
import dev.ide.model.Coordinate
import dev.ide.model.Module
import dev.ide.model.bridge.DependencyModelBridge
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.ProgressReporter
import dev.ide.platform.log.Log

/**
 * Resolving this host's dependencies: the network half of dependency management, over the model half.
 *
 * **The declarations live in the project model**, exactly as they do on the desktop and Android hosts: one
 * `LibraryDependency` per declared coordinate in the module's `module.toml`, and the resolved closure
 * attached to a library in `.platform/libraries.json`. Reading and writing those is
 * [DependencyModelBridge]'s, shared with every other host; what is here is what this host does differently,
 * which is where a jar comes from and what the standard library counts as.
 *
 * They used to live in a flat `.platform/dependencies` file, written when this host had no model to put them
 * in. That file is migrated on open (see [IosProjectModel]) and a project's dependencies now mean the same
 * thing on every host that opens it.
 *
 * **The standard library is the HOST's dependency, not the user's,** and is deliberately NOT in the model.
 * Every Kotlin file depends on it and a project whose editor cannot resolve `List` is not worth opening, so
 * it joins the analysis classpath whether or not anything declared it. Declaring it into `module.toml`
 * instead would write this host's Kotlin version into a project the other hosts already answer for with
 * their own bundled stdlib, and would put a row on the Dependencies screen whose remove button has to
 * refuse. It is a [hostClasspath] entry, where its version is still visible and nothing pretends it can be
 * taken away. A project that DOES declare `kotlin-stdlib` overrides it, which is the one case where the user
 * should win.
 *
 * **The cache IS the offline repository.** [ResolverCache] lays artifacts out in Maven's own directory
 * layout, so a coordinate already downloaded resolves with no network at all: the fetch happens once per
 * project, adding a second dependency re-downloads nothing, and every later open is a directory check.
 *
 * **What a classpath buys, precisely.** A jar makes library code RESOLVABLE: a type resolves by FQN or
 * through an explicit `import`, and its members come back from the jar. What makes it DISCOVERABLE, a bare
 * simple name through a default wildcard import or `println` offered by name, is a lookup from a name to the
 * things that could satisfy it, which is what an index is. `IosKotlinAnalysis` builds one over these jars;
 * without it the jars would resolve what you name and offer nothing.
 *
 * Nothing here is required for the editor to start. [classpathJars] answers from the model and the disk and
 * never touches the network, so completion is available the instant a project opens; [resolveInto] improves
 * it when it succeeds and is ignored when it does not (a phone with no signal is the ordinary case, not an
 * error).
 *
 * [fetcher] is the module's one I/O seam, injected for the reason the resolver injects it: a test drives the
 * whole path against a fixture and never opens a socket.
 */
internal class IosDependencies(
    private val projectRoot: String,
    private val fetcher: ArtifactFetcher = HttpArtifactFetcher(),
) {

    private val cache = ResolverCache(projectRoot)

    /** The model operations, over whichever store the host hands in. */
    private fun model(store: ProjectModelStore) = DependencyModelBridge(store)

    // ---- the classpath ------------------------------------------------------------------------------

    /**
     * Every library jar the open project can see, plus the host's own.
     *
     * The UNION across modules, not one module's classpath, because the analysis on this host indexes the
     * whole project as one source model: there is no per-module compilation to keep classpaths apart for,
     * and splitting them would mean a file resolving differently depending on which module claimed it.
     */
    fun classpathJars(store: ProjectModelStore?): List<String> {
        if (store == null) return hostClasspath()
        val bridge = model(store)
        return (hostClasspath(declaresStdlib(bridge, store)) + bridge.workspaceClasspath()).distinct()
    }

    /**
     * The jars this HOST puts on every classpath: the Kotlin standard library, when nothing declared one.
     *
     * Answered from the cache alone. A first open with no signal therefore has no stdlib and says so through
     * the editor rather than through an error, and the next open with signal has one.
     */
    fun hostClasspath(declaresStdlib: Boolean = false): List<String> =
        if (declaresStdlib) emptyList()
        else listOfNotNull(cache.fileFor(cache.relativePath(STDLIB, "jar")).takeIf { IosFiles.exists(it) })

    /** True when some module declares the standard library itself, so the host must not add its own. */
    private fun declaresStdlib(bridge: DependencyModelBridge, store: ProjectModelStore): Boolean =
        store.workspace.projects.flatMap { it.modules }.any { module ->
            bridge.declared(module).any { it.coordinate.group == STDLIB.group && it.coordinate.name == STDLIB.name }
        }

    // ---- resolution ---------------------------------------------------------------------------------

    /**
     * Resolve everything [module] declares as ONE graph and attach the result to the model.
     *
     * One graph rather than one resolve per declaration, because that is what makes conflict resolution mean
     * anything: a single version per `group:name` across the whole closure, and a transitive that only a
     * superseded version pulled in is pruned, exactly as Gradle and Maven produce.
     *
     * The host's stdlib is resolved alongside it and attached to NOTHING: it is not a declaration, so it
     * gets no library and no row, only a jar in the cache that [hostClasspath] finds.
     *
     * Null when resolution fails outright, which leaves the previous libraries intact: an offline open must
     * not empty a classpath that was correct yesterday.
     */
    suspend fun resolveInto(
        store: ProjectModelStore,
        module: Module,
        progress: ProgressReporter = SilentProgress,
    ): ResolutionResult? {
        val bridge = model(store)
        val declarations = bridge.declared(module)
        val wantsStdlib = declarations.none { it.coordinate.group == STDLIB.group && it.coordinate.name == STDLIB.name }
        val roots = (if (wantsStdlib) listOf(STDLIB) else emptyList()) + declarations.map { it.coordinate }
        if (roots.isEmpty()) return null
        val exclusions = declarations.filter { it.exclusions.isNotEmpty() }
            .associate { it.coordinate to it.exclusions }
        val result = runCatching {
            resolver().resolve(roots, REPOSITORIES, ConflictPolicy.NEWEST, progress, exclusions = exclusions)
        }.onFailure {
            log.warn("resolve failed for $projectRoot: ${it.message}")
        }.getOrNull() ?: return null

        bridge.attach(module, result)
        if (result.unresolved.isNotEmpty()) {
            log.warn("classpath incomplete for ${module.name}: ${result.unresolved.joinToString()}")
        }
        return result
    }

    /**
     * Resolve every module of the open project if anything it declares is missing, then report the jars.
     *
     * The cheap path is the common one: with everything attached and cached this is a handful of `stat`
     * calls and no resolver at all, which is what makes it safe to call on every project open.
     */
    suspend fun ensure(store: ProjectModelStore?): List<String> {
        if (store == null) return emptyList()
        val bridge = model(store)
        val modules = store.workspace.projects.flatMap { it.modules }
        // A declaration with no library behind it has never resolved here, and a missing host stdlib is the
        // first open of a project on a device that has not fetched one. Either is a reason to resolve;
        // neither is a reason to fail.
        val missingLibrary = modules.any { !bridge.fullyAttached(it) }
        val missingStdlib = !declaresStdlib(bridge, store) && hostClasspath().isEmpty()
        if (missingLibrary || missingStdlib) for (module in modules) resolveInto(store, module)
        return classpathJars(store)
    }

    // ---- the picker ---------------------------------------------------------------------------------

    /**
     * Repository search, for the add-dependency picker.
     *
     * Reports whether any index could be read, not just what matched: a phone with no signal and a query for
     * an artifact that does not exist both produce no hits, and only one of them is an answer.
     */
    suspend fun search(query: String, limit: Int = 25): ArtifactSearch =
        runCatching { resolver().searchWithStatus(query, limit) }
            .onFailure { log.warn("artifact search failed for '$query': ${it.message}") }
            .getOrDefault(ArtifactSearch(emptyList(), indexUnavailable = true))

    /** Published versions of `group:name`, for the version picker. */
    suspend fun availableVersions(group: String, name: String): List<String> =
        runCatching { resolver().availableVersions(group, name, REPOSITORIES) }
            .onFailure { log.warn("version list failed for $group:$name: ${it.message}") }
            .getOrDefault(emptyList())

    /** Versions of `group:name` downloaded into this project's cache, with their size on disk. */
    fun cachedVersions(group: String, name: String): List<Pair<String, Long>> =
        cache.cachedVersions(group, name)

    /** Evict one cached version. The next resolve that wants it downloads it again. */
    fun deleteCachedVersion(group: String, name: String, version: String): Boolean =
        cache.deleteVersion(group, name, version)

    /**
     * Forget every artifact recorded as absent, so the next resolve probes the network for it again.
     *
     * A confirmed 404 is remembered for a week, which is what stops every open re-probing the `-sources.jar`
     * most libraries never publish. It also means an artifact that was genuinely missing and has since been
     * published stays missing until the TTL expires, so an explicit Retry has to clear it, or the button
     * does nothing for the one case a user presses it in. Positive results are untouched: a released POM is
     * immutable, so this costs only the re-probe of what really was not found.
     */
    fun forgetAbsentArtifacts() = cache.clearMisses()

    private fun resolver() = MavenDependencyResolver(cache, ::IosVirtualFile, fetcher)

    /** The resolver reports download progress; the caller supplies a reporter when anything draws it. */
    object SilentProgress : ProgressReporter {
        override fun report(fraction: Double, message: String?) = Unit
        override fun checkCanceled() = Unit
        override val isCanceled: Boolean get() = false
    }

    companion object {
        private val log = Log.logger("ios-deps")

        /**
         * The Kotlin version this IDE's editor targets.
         *
         * Held in lockstep with `libs.versions.toml`'s `kotlin` and with `BundledKotlinStdlib.VERSION`, which
         * is what the other hosts bundle. A project that resolves a different stdlib is not wrong, but the
         * built-ins the editor reasons about should be the ones its own analysis was written against.
         */
        const val KOTLIN_VERSION = "2.4.0"

        val STDLIB = Coordinate("org.jetbrains.kotlin", "kotlin-stdlib", KOTLIN_VERSION)

        val REPOSITORIES = listOf(
            Repository("Maven Central", "https://repo1.maven.org/maven2"),
            // Every `androidx.*` / `com.google.android.*` artifact lives here and is not mirrored to Central,
            // so a project that wants one cannot resolve it without this repository.
            Repository("Google", "https://dl.google.com/dl/android/maven2"),
        )
    }
}
