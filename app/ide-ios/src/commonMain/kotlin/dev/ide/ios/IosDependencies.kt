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
import dev.ide.platform.ProgressReporter
import dev.ide.platform.log.Log

/**
 * What a project on this host depends on, and where its library classes come from.
 *
 * Those are one question here, not two. The other hosts keep declarations in the project model (`module.toml`
 * via `ProjectModelStore`) and ship `kotlin-stdlib.jar` as a classpath RESOURCE to extract; this host has
 * neither a project model nor a resource to extract, so both answers come from the same place: a file listing
 * declarations, and the Maven resolver that turns them into jars.
 *
 * **The declaration file.** `.platform/dependencies`, one `scope group:name:version` per line. A deliberately
 * dull format: read and written by this class alone, fixable by hand in the editor when something goes wrong,
 * and carrying exactly what the resolver needs and nothing else. When this host grows a real project model
 * these move into it, and this file becomes a migration.
 *
 * **The standard library is the HOST's dependency, not the user's.** Every Kotlin file depends on it and a
 * project whose editor cannot resolve `List` is not worth opening, so it joins every resolution whether or
 * not anything declared it — but it is deliberately kept out of [declared]. A user who declared nothing has
 * declared nothing, and showing the stdlib as their declaration would put a row on the Dependencies screen
 * whose remove button has to refuse, silently, for the editor's sake. It appears in the resolved graph
 * instead, where its version is still visible and nothing pretends it can be taken away. A project that DOES
 * declare `kotlin-stdlib` overrides the version, which is the one case where the user should win.
 *
 * **The cache IS the offline repository.** [ResolverCache] lays artifacts out in Maven's own directory
 * layout, so a coordinate already downloaded resolves with no network at all: the fetch happens once per
 * project, adding a second dependency re-downloads nothing, and every later open is a directory check.
 *
 * **What a classpath buys, precisely.** A jar makes library code RESOLVABLE: a type resolves by FQN or
 * through an explicit `import`, and its members come back from the jar. What makes it DISCOVERABLE — a bare
 * simple name through a default wildcard import, or `println` offered by name — is a lookup from a name to
 * the things that could satisfy it, which is what an index is. `IosKotlinAnalysis` builds one over these
 * jars; without it the jars would resolve what you name and offer nothing.
 *
 * Nothing here is required for the editor to start. [cachedJars] answers from disk and never touches the
 * network, so completion is available the instant a project opens; [resolve] improves it when it succeeds and
 * is ignored when it does not (a phone with no signal is the ordinary case, not an error).
 *
 * [fetcher] is the module's one I/O seam, injected for the reason the resolver injects it: a test drives the
 * whole path against a fixture and never opens a socket.
 */
internal class IosDependencies(
    private val projectRoot: String,
    private val fetcher: ArtifactFetcher = HttpArtifactFetcher(),
) {

    private val cache = ResolverCache(projectRoot)
    private val file = IosFiles.join(projectRoot, DECLARATIONS)

    /** A declared dependency: a coordinate, and the configuration it was declared on. */
    data class Declaration(val coordinate: Coordinate, val scope: String)

    // ---- declarations -------------------------------------------------------------------------------

    /**
     * What the user declared, in declaration order. Empty for a project nobody has added anything to.
     *
     * Lines that do not parse are skipped rather than failing the read: this file is hand-editable, and one
     * bad line must not cost a user the rest of their dependencies.
     */
    fun declared(): List<Declaration> =
        if (!IosFiles.exists(file)) emptyList() else IosFiles.readText(file)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val scope = line.substringBefore(' ', DEFAULT_SCOPE).ifBlank { DEFAULT_SCOPE }
                val rest = line.substringAfter(' ', line).trim()
                Coordinate.parseOrNull(rest)?.let { Declaration(it, scope) }
            }
            .toList()

    /**
     * Everything to resolve: what the user declared, plus the standard library.
     *
     * The stdlib goes first so it is fetched first — on a slow connection that is the one artifact whose
     * absence is felt — and is dropped when the project declares that artifact itself, so a user pinning a
     * different Kotlin version gets the version they asked for rather than two.
     */
    fun resolutionSet(): List<Coordinate> {
        val userDeclared = declared().map { it.coordinate }
        return if (userDeclared.any { it.isSameArtifactAs(STDLIB) }) userDeclared
        else listOf(STDLIB) + userDeclared
    }

    /** Declare [coordinate] on [scope]. False when this `group:name` is already declared. */
    fun add(coordinate: Coordinate, scope: String = DEFAULT_SCOPE): Boolean {
        val current = declared()
        if (current.any { it.coordinate.isSameArtifactAs(coordinate) }) return false
        write(current + Declaration(coordinate, scope))
        return true
    }

    /**
     * Undeclare [coordinate], matched on `group:name`: the version is not part of the identity a user removes
     * by, and the row they tapped may carry a version conflict resolution picked rather than the one declared.
     */
    fun remove(coordinate: Coordinate): Boolean {
        val current = declared()
        val kept = current.filterNot { it.coordinate.isSameArtifactAs(coordinate) }
        if (kept.size == current.size) return false
        write(kept)
        return true
    }

    private fun write(declarations: List<Declaration>) {
        IosFiles.parentOf(file)?.let { IosFiles.mkdirs(it) }
        IosFiles.writeText(
            file,
            buildString {
                append("# CodeAssist dependencies: one `scope group:name:version` per line.\n")
                for (d in declarations) append(d.scope).append(' ').append(d.coordinate).append('\n')
            },
        )
    }

    // ---- resolution ---------------------------------------------------------------------------------

    /**
     * The jars already on disk for what is declared. No network, so it is safe on the completion path.
     *
     * Read back from the resolver's cache rather than remembered in a side file: the cache is the record, and
     * a jar that is present is usable whether this process put it there or a previous one did. A declared
     * coordinate with nothing cached simply contributes nothing, which is what an unresolved dependency
     * should cost.
     */
    fun cachedJars(): List<String> = resolutionSet().mapNotNull { coordinate ->
        cache.fileFor(cache.relativePath(coordinate, "jar")).takeIf { IosFiles.exists(it) }
    }

    /**
     * Resolve every declaration, downloading what is missing, and return the full graph.
     *
     * Suspends and may go to the network. A failure is logged and returns null: the caller carries on with
     * whatever [cachedJars] holds.
     */
    suspend fun resolve(progress: ProgressReporter = SilentProgress): ResolutionResult? =
        runCatching {
            resolver().resolve(resolutionSet(), REPOSITORIES, ConflictPolicy.NEWEST, progress)
        }.onFailure {
            log.warn("resolve failed for $projectRoot: ${it.message}")
        }.getOrNull()

    /**
     * Resolve if anything declared is not cached yet, then report the jars on disk.
     *
     * The cheap path is the common one: with everything cached this is a handful of `stat` calls and no
     * resolver at all, which is what makes it safe to call on every project open.
     */
    suspend fun ensure(): List<String> {
        val wanted = resolutionSet()
        val already = cachedJars()
        if (already.size == wanted.size) return already
        val result = resolve() ?: return already
        if (result.unresolved.isNotEmpty()) {
            log.warn("classpath incomplete for $projectRoot: ${result.unresolved.joinToString()}")
        }
        return cachedJars()
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
     * published stays missing until the TTL expires — so an explicit Retry has to clear it, or the button
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

        private const val DECLARATIONS = ".platform/dependencies"

        /** Gradle's default configuration name, and the only one this host has a use for yet. */
        const val DEFAULT_SCOPE = "implementation"

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

        /** Two coordinates naming the same artifact, whatever versions they carry. */
        private fun Coordinate.isSameArtifactAs(other: Coordinate) =
            group == other.group && name == other.name
    }
}
