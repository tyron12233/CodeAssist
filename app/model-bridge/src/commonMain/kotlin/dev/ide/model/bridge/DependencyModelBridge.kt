package dev.ide.model.bridge

import dev.ide.deps.ArtifactKind
import dev.ide.deps.ResolutionResult
import dev.ide.deps.impl.DependencyPartition
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.Exclusion
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.Project
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.fileInfo

/**
 * A module's DECLARED dependencies, and the resolved closure behind them, as model operations.
 *
 * The declaration is the durable thing: one [LibraryDependency] per coordinate in the module's
 * `module.toml`, which is what every host reads and what a project carries from one to another. The closure
 * is derived: a resolve produces artifacts, they are partitioned back across the declarations
 * ([DependencyPartition]) and written into the library table as one library per declaration, which is what
 * `Module.classpath` then answers with.
 *
 * What is NOT here is resolution policy or presentation. Which repositories to use, whether to go to the
 * network at all, what an add means for a build file, and how a graph is drawn are the host's:
 * :ide-core adds Android compatibility, platforms (BOMs), local libraries, build-variant qualifiers and
 * build-file declaration on top of these same operations, and the iOS host adds none of it.
 */
class DependencyModelBridge(private val store: ProjectModelStore) {

    /** A declared library dependency: the coordinate, and the configuration it was declared on. */
    data class Declaration(
        val coordinate: Coordinate,
        val scope: String,
        val exclusions: List<Exclusion> = emptyList(),
    )

    /**
     * What [module] declares, in declaration order.
     *
     * Only the entries that parse as a Maven coordinate. A module-on-module dependency is a declaration too,
     * but it names no artifact; [moduleDependencies] answers those.
     */
    fun declared(module: Module): List<Declaration> = module.dependencies
        .filterIsInstance<LibraryDependency>()
        .mapNotNull { entry ->
            Coordinate.parseOrNull(entry.library.name)
                ?.let { Declaration(it, entry.scope.id, entry.exclusions) }
        }

    /** [module]'s module-on-module declarations. */
    fun moduleDependencies(module: Module): List<ModuleDependency> =
        module.dependencies.filterIsInstance<ModuleDependency>()

    /**
     * Declare [coordinate] on [module]. False when this `group:name` is already declared.
     *
     * The commit and the save are one step: a declaration that reaches memory and not `module.toml` is gone
     * at the next open.
     */
    fun declare(
        project: Project,
        module: Module,
        coordinate: Coordinate,
        scope: String = DependencyScope.IMPLEMENTATION.id,
        exclusions: List<Exclusion> = emptyList(),
        variant: String? = null,
    ): Boolean {
        if (declared(module).any { it.coordinate.isSameArtifactAs(coordinate) }) return false
        project.beginModification().apply {
            module(module.id).addDependency(
                LibraryDependency(
                    LibraryRef(coordinate.toString()), scopeOf(scope), exclusions = exclusions, variant = variant,
                ),
            )
            commit()
        }
        store.save()
        return true
    }

    /**
     * Undeclare [coordinate] from [module], matched on `group:name`: the version is not part of the identity
     * a user removes by, and the row they tapped may carry a conflict-resolved version rather than the
     * declared one.
     */
    fun undeclare(project: Project, module: Module, coordinate: Coordinate): Boolean {
        val entries = module.dependencies.filterIsInstance<LibraryDependency>().filter { entry ->
            Coordinate.parseOrNull(entry.library.name)?.isSameArtifactAs(coordinate) == true
        }
        if (entries.isEmpty()) return false
        project.beginModification().apply {
            val m = module(module.id)
            entries.forEach { m.removeDependency(it) }
            commit()
        }
        store.save()
        return true
    }

    /** Declare a dependency of [module] on [target], at [scope]. False when it is already declared. */
    fun declareModule(
        project: Project,
        module: Module,
        target: Module,
        scope: String = DependencyScope.IMPLEMENTATION.id,
        variant: String? = null,
    ): Boolean {
        if (module.id == target.id) return false
        if (moduleDependencies(module).any { it.target == target.id }) return false
        project.beginModification().apply {
            module(module.id).addDependency(ModuleDependency(target.id, scopeOf(scope), variant = variant))
            commit()
        }
        store.save()
        return true
    }

    /**
     * Modules [module] may depend on: every other module of the workspace that does not already depend on
     * it, directly or transitively, and is not already a dependency.
     *
     * The reachability check is what keeps a cycle out of the model, which no later stage would enjoy
     * discovering.
     */
    fun moduleDependencyTargets(module: Module): List<Module> {
        val all = store.workspace.projects.flatMap { it.modules }
        val existing = moduleDependencies(module).map { it.target }.toSet()
        return all.filter { candidate ->
            candidate.id != module.id && candidate.id !in existing && !dependsOn(candidate, module.id, all)
        }
    }

    private fun dependsOn(from: Module, targetId: dev.ide.model.ModuleId, all: List<Module>): Boolean {
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current.id.value)) continue
            for (entry in moduleDependencies(current)) {
                if (entry.target == targetId) return true
                all.firstOrNull { it.id == entry.target }?.let { queue.add(it) }
            }
        }
        return false
    }

    /**
     * Write [result]'s closure into the library table, one library per declaration of [module].
     *
     * One graph in, one library per declarer out: the artifacts are partitioned so each declaration owns the
     * part of the closure it is the first to reach, which keeps the per-library model while the UNION of the
     * libraries is exactly the whole-graph closure. No duplication, and no drift from resolving each
     * declaration on its own.
     *
     * A declaration whose own artifact did not resolve keeps whatever library it already had rather than
     * being emptied: offline, the last classpath that worked is the correct answer. Returns whether anything
     * changed, and saves only then, because a save rewrites every `module.toml` in the workspace.
     */
    fun attach(module: Module, result: ResolutionResult): Boolean {
        val directs = declared(module).map { it.coordinate.toString() to it.coordinate }
        if (directs.isEmpty()) return false
        val partition = DependencyPartition.partition(directs, result.resolved)
        val byCoordinate = result.resolved.associateBy { it.coordinate }
        var changed = false
        for ((libraryName, coordinate) in directs) {
            val artifacts = partition[libraryName].orEmpty()
            if (artifacts.isEmpty()) continue
            val classes = artifacts
                .flatMap { listOf(it.classesRoot.path) + it.extraClassesRoots.map { r -> r.path } }
                .toSet()
            val sources = artifacts.mapNotNull { it.sourcesRoot?.path }.toSet()
            val existing = library(libraryName)
            if (existing != null &&
                existing.classesRoots.map { it.path }.toSet() == classes &&
                existing.sourcesRoots.map { it.path }.toSet() == sources
            ) continue
            val primary = byCoordinate[coordinate]
                ?: result.resolved.firstOrNull {
                    it.coordinate.group == coordinate.group && it.coordinate.name == coordinate.name
                }
            store.workspace.libraryTable.create(libraryName).apply {
                kind = if (primary?.kind == ArtifactKind.AAR) LibraryKind.AAR else LibraryKind.JAR
                artifacts.forEach { artifact ->
                    addClassesRoot(artifact.classesRoot)
                    artifact.extraClassesRoots.forEach { addClassesRoot(it) }
                    artifact.sourcesRoot?.let { addSourcesRoot(it) }
                }
                commit()
            }
            changed = true
        }
        if (changed) store.save()
        return changed
    }

    /** The library named [name], from the workspace table or any project's. */
    fun library(name: String) = store.workspace.libraryTable.byName(name)
        ?: store.workspace.projects.firstNotNullOfOrNull { it.libraryTable.byName(name) }

    /** True when every declaration of [module] has a library behind it, so nothing needs resolving. */
    fun fullyAttached(module: Module): Boolean =
        declared(module).all { library(it.coordinate.toString()) != null }

    /**
     * The library jars on [module]'s compile classpath that exist on disk.
     *
     * Read off the model rather than recomputed from the declarations, so this answers with the closure that
     * was actually downloaded, transitives included. Existence is checked because a cache can be cleared
     * under a model that still names its contents.
     */
    fun libraryClasspath(module: Module): List<String> =
        module.classpath(DependencyScope.IMPLEMENTATION).entries
            .filter { it.kind == ClasspathEntryKind.LIBRARY }
            .map { it.root.path }
            .filter { fileInfo(it) != null }

    /** [libraryClasspath] over every module of the workspace, deduplicated. */
    fun workspaceClasspath(): List<String> =
        store.workspace.projects.flatMap { it.modules }.flatMap { libraryClasspath(it) }.distinct()

    companion object {
        /**
         * The [DependencyScope] a UI configuration chip names.
         *
         * The spellings are matched loosely because the label arrives from several places (a chip, a
         * migrated file, an API caller); anything else is looked up among the registered scopes by
         * configuration id then persisted name, so a plugin-defined scope resolves to the real one with its
         * real classpath semantics instead of silently becoming `implementation`.
         */
        fun scopeOf(label: String): DependencyScope =
            when (label.lowercase().replace("_", "").replace("-", "")) {
                "api" -> DependencyScope.API
                "compileonly" -> DependencyScope.COMPILE_ONLY
                "runtimeonly" -> DependencyScope.RUNTIME_ONLY
                "testimplementation", "test" -> DependencyScope.TEST_IMPLEMENTATION
                "natives" -> DependencyScope.NATIVES
                else -> DependencyScope.byId(label)
                    ?: DependencyScope.registered().firstOrNull { it.name == label }
                    ?: DependencyScope.IMPLEMENTATION
            }

        /** Two coordinates naming the same artifact, whatever versions they carry. */
        fun Coordinate.isSameArtifactAs(other: Coordinate): Boolean =
            group == other.group && name == other.name
    }
}
