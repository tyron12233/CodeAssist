package dev.ide.model

import dev.ide.platform.ServiceKey

/**
 * Maven dependencies, as the Dependencies screen sees them: search the repositories, list what versions an
 * artifact has, read a module's resolved graph (with what a BOM pinned and where versions clash), and add a
 * library, a BOM or a repository the way the screen does.
 *
 * What it unlocks is the "add the library for this" move: a quick fix that notices an unresolved
 * `retrofit2.Retrofit` can check the coordinate exists and which version is current before offering to add
 * it, rather than writing a guess into the build file. A plugin could already attach a `LibraryDependency`
 * through the model, but that writes a declaration nothing has resolved.
 *
 * Every call that reaches a repository suspends, and an unreachable one answers empty or with a failure
 * rather than throwing, so call it off the main thread and treat "no versions" as "could not tell", not as
 * "does not exist". Adding goes through the same path as the screen: it resolves first, refuses what the
 * module cannot take (an `.aar` on a plain Java module), writes the build file, and re-indexes, with the
 * progress shown to the user. Declare `dev.ide.plugin.PluginCapabilities.MODEL_DEPENDENCIES` to add.
 */
interface ModuleDependencies {
    /**
     * Search the repositories' index for [query] (a name, `group:name`, or words). Each hit says whether
     * [module] can take it; with no module, everything reads as compatible.
     */
    suspend fun search(query: String, module: Module? = null): ArtifactSearch

    /** The versions published for [groupAndName] (`com.squareup.retrofit2:retrofit`), newest first. */
    suspend fun versions(groupAndName: String): List<String>

    /**
     * [module]'s dependencies resolved together: what it declares, every artifact in the graph, the version
     * conflicts and what won, and what no repository had. Cached on disk after the first resolution; when the
     * network is out it degrades to the declared entries, all unresolved. Null for an unknown module.
     */
    suspend fun resolved(module: Module): ResolvedDependencies?

    /**
     * Resolve [coordinate] (`group:name:version`, or `group:name` for one a BOM pins) and add it to [module]
     * in [scope]. Fails rather than adding something unresolvable or incompatible.
     */
    suspend fun add(
        module: Module,
        coordinate: String,
        scope: DependencyScope = DependencyScope.IMPLEMENTATION,
    ): DependencyChange

    /** Add a BOM (`group:name:version`) to [module], so the libraries it covers can be declared without a version. */
    suspend fun addPlatform(module: Module, coordinate: String): DependencyChange

    /** The repositories resolution reads, the built-in ones first. */
    fun repositories(): List<MavenRepository>

    /**
     * Add a Maven repository for every module. Refused (false) for a URL that is not http(s) or that is
     * already listed. Persisted with the user's other repositories, where they can remove it.
     */
    fun addRepository(name: String, url: String): Boolean
}

/** WORKSPACE-scoped [ModuleDependencies] for the open project. */
val MODULE_DEPENDENCIES = ServiceKey<ModuleDependencies>("platform.moduleDependencies")

/** What a [ModuleDependencies.search] found. An interface, as the rest here, so it can grow without a break. */
interface ArtifactSearch {
    val hits: List<ArtifactHit>

    /** No index could be reached, so an empty [hits] means "could not search", not "nothing matches". */
    val indexUnavailable: Boolean
}

interface ArtifactHit {
    /** `group:name:version`, the latest the index knows. */
    val coordinate: String

    /** `jar`, `aar`, `pom`, … */
    val packaging: String

    /** Whether the module asked about can take it; [incompatibleReason] says why not. */
    val compatible: Boolean
    val incompatibleReason: String?
}

interface ResolvedDependencies {
    /** The declared entries, in declaration order. */
    val declared: List<ResolvedArtifact>

    /** Every artifact in the graph, declared and transitive, once each. */
    val artifacts: List<ResolvedArtifact>
    val conflicts: List<VersionConflict>

    /** Coordinates no repository could resolve. */
    val unresolved: List<String>
}

interface ResolvedArtifact {
    /** `group:name:version` as resolved, so a BOM-pinned declaration shows the version the BOM chose. */
    val coordinate: String
    val group: String
    val name: String
    val version: String

    /** Declared by the module itself, rather than pulled in by something it declares. */
    val declared: Boolean

    /** The declared scope's id (`implementation`), for a declared entry; null for a transitive one. */
    val scope: String?

    /** `group:name:version` of what this one depends on, as resolved. */
    val dependsOn: List<String>
}

/** [artifact] (`group:name`) was asked for at each of [requested]; [chosen] is what the build uses. */
interface VersionConflict {
    val artifact: String
    val requested: List<String>
    val chosen: String
}

/** The outcome of an add: whether it went in, and the user-facing account of what happened either way. */
interface DependencyChange {
    val added: Boolean
    val message: String
}

interface MavenRepository {
    val name: String
    val url: String

    /** One the IDE ships with (Maven Central, Google), which cannot be removed. */
    val builtIn: Boolean
}
