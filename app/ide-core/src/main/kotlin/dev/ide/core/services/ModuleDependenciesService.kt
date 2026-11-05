package dev.ide.core.services

import dev.ide.model.ArtifactHit
import dev.ide.model.ArtifactSearch
import dev.ide.model.DependencyChange
import dev.ide.model.DependencyScope
import dev.ide.model.MavenRepository
import dev.ide.model.Module
import dev.ide.model.ModuleDependencies
import dev.ide.model.ResolvedArtifact
import dev.ide.model.ResolvedDependencies
import dev.ide.model.VersionConflict
import dev.ide.ui.backend.UiDepKind

/**
 * The published [ModuleDependencies] over the engine's [DependencyService], the same instance the Dependencies
 * screen drives, so a plugin's add shows the same progress, writes the same build file and triggers the same
 * re-index as the user's own.
 *
 * Only Maven artifacts cross: the screen's graph also carries module dependencies, the SDK and local jars,
 * which have no coordinate a plugin could act on, and the model already describes them.
 */
internal class ModuleDependenciesService(private val deps: DependencyService) : ModuleDependencies {

    override suspend fun search(query: String, module: Module?): ArtifactSearch {
        // An empty module name finds no module, which the engine reads as "no constraint": everything compatible.
        val found = deps.artifactSearch(query, module?.name.orEmpty())
        return Search(
            hits = found.hits.map { Hit(it.coordinate, it.packaging, it.compatible, it.incompatibleReason) },
            indexUnavailable = found.indexUnavailable,
        )
    }

    override suspend fun versions(groupAndName: String): List<String> =
        deps.availableVersions(moduleName = "", coordinate = groupAndName)

    override suspend fun resolved(module: Module): ResolvedDependencies? {
        val graph = deps.moduleDependencies(module.name) ?: return null
        fun maven(kind: UiDepKind, local: Boolean, group: String) =
            !local && group.isNotBlank() && (kind == UiDepKind.Jar || kind == UiDepKind.Aar || kind == UiDepKind.Platform)
        val artifacts = graph.nodes.filter { maven(it.kind, it.local, it.group) }.map {
            Artifact(it.coordinate, it.group, it.name, it.version, it.declared, it.scope, it.children)
        }
        val byCoordinate = artifacts.associateBy { it.coordinate }
        return Resolved(
            declared = graph.declared.filter { maven(it.kind, it.local, it.group) }
                .map { byCoordinate[it.coordinate] ?: Artifact(it.coordinate, it.group, it.name, it.version, true, it.scope, it.children) },
            artifacts = artifacts,
            conflicts = graph.conflicts.map { Conflict(it.artifact, it.requested, it.chosen) },
            unresolved = graph.unresolved,
        )
    }

    override suspend fun add(module: Module, coordinate: String, scope: DependencyScope): DependencyChange =
        deps.addDependency(module.name, coordinate, scope.id).let { Change(it.success, it.message) }

    override suspend fun addPlatform(module: Module, coordinate: String): DependencyChange =
        deps.addPlatform(module.name, coordinate).let { Change(it.success, it.message) }

    override fun repositories(): List<MavenRepository> = deps.repositories().map { Repo(it.name, it.url, it.builtin) }

    override fun addRepository(name: String, url: String): Boolean = deps.addRepository(name, url)

    private data class Search(override val hits: List<ArtifactHit>, override val indexUnavailable: Boolean) : ArtifactSearch

    private data class Hit(
        override val coordinate: String,
        override val packaging: String,
        override val compatible: Boolean,
        override val incompatibleReason: String?,
    ) : ArtifactHit

    private data class Resolved(
        override val declared: List<ResolvedArtifact>,
        override val artifacts: List<ResolvedArtifact>,
        override val conflicts: List<VersionConflict>,
        override val unresolved: List<String>,
    ) : ResolvedDependencies

    private data class Artifact(
        override val coordinate: String,
        override val group: String,
        override val name: String,
        override val version: String,
        override val declared: Boolean,
        override val scope: String?,
        override val dependsOn: List<String>,
    ) : ResolvedArtifact

    private data class Conflict(
        override val artifact: String,
        override val requested: List<String>,
        override val chosen: String,
    ) : VersionConflict

    private data class Change(override val added: Boolean, override val message: String) : DependencyChange

    private data class Repo(override val name: String, override val url: String, override val builtIn: Boolean) : MavenRepository
}
