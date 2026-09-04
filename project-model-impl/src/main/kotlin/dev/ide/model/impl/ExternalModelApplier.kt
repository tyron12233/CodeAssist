package dev.ide.model.impl

import dev.ide.model.DependencyScope
import dev.ide.model.FacetData
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryRef
import dev.ide.model.ModifiableModule
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.PlatformDependency
import dev.ide.model.SourceSetTemplate
import dev.ide.model.sanitizeCoordinate
import dev.ide.model.sanitizeLibraryName
import dev.ide.model.sync.ExternalDependency
import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.ExternalModule
import dev.ide.model.sync.ExternalModuleRef
import dev.ide.model.sync.ExternalPlatform
import dev.ide.model.sync.ExternalProjectModel

/**
 * Applies a [ExternalProjectModel] snapshot (what a [dev.ide.model.sync.ProjectImporter] read out of a
 * foreign build system) to the live model, in one transaction per level.
 *
 * The division of labour is what keeps importers small: an importer produces data, this applies it. Only
 * this class knows about transactions, module-type resolution, facet codecs, and what a re-sync is allowed
 * to remove.
 *
 * Removal is driven by the [previous] snapshot rather than by absence alone: a module the last sync produced
 * and this one no longer reports is removed, while a module the IDE has that no sync ever produced is left
 * alone (it was added in the IDE, and dropping it would be data loss). With no previous snapshot nothing is
 * removed.
 */
class ExternalModelApplier(private val store: ProjectModelStore) {

    /** What one [apply] changed, for the sync message the user sees. */
    data class Report(
        val projectCreated: Boolean = false,
        val added: List<String> = emptyList(),
        val updated: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
    ) {
        val changed: Boolean get() = projectCreated || added.isNotEmpty() || updated.isNotEmpty() || removed.isNotEmpty()
    }

    /**
     * Merge [model] into the workspace: create the project if the workspace is empty, rebind its build
     * system to the importer's, then add / refresh / remove modules. [defaultLanguageLevel] is used for a
     * module whose snapshot leaves [ExternalModule.languageLevel] null.
     *
     * [removeAbsent] widens removal to every module the snapshot doesn't declare, which is right when the
     * build files own the whole model ([dev.ide.model.sync.ModelOwnership.EXTERNAL]): unlinking a module from
     * them removes it from the IDE, exactly as it removes it from the build.
     */
    fun apply(
        model: ExternalProjectModel,
        defaultLanguageLevel: LanguageLevel = LanguageLevel.JAVA_17,
        previous: ExternalProjectModel? = null,
        removeAbsent: Boolean = false,
    ): Report {
        val created = ensureProject(model)
        val project = store.workspace.projects.firstOrNull() ?: return Report(projectCreated = created)

        val existing = project.modules.associateBy { it.name }
        val declared = model.modules.mapTo(HashSet()) { it.name }
        // Removable: what a previous sync produced and this one dropped, plus (when the build files own the
        // model) everything else the snapshot no longer declares.
        val candidates =
            if (removeAbsent) existing.keys.toList() else previous?.modules?.map { it.name }.orEmpty()
        val gone = candidates.distinct().filter { it !in declared && it in existing }
        val added = ArrayList<String>()
        val updated = ArrayList<String>()

        val tx = project.beginModification()
        try {
            for (name in gone) existing[name]?.let { tx.removeModule(it.id) }
            for (external in model.modules) {
                val current = existing[external.name]
                if (current == null) {
                    val module = tx.addModule(external.name, store.moduleTypes.resolve(external.typeId))
                    configure(module, external, defaultLanguageLevel, replaceDependencies = false)
                    added += external.name
                } else {
                    configure(tx.module(current.id), external, current.languageLevel, replaceDependencies = true, current = current)
                    updated += external.name
                }
            }
            tx.commit()
        } catch (e: Throwable) {
            tx.dispose()
            throw e
        }
        return Report(created, added, updated, gone)
    }

    /** Create the project when the workspace has none; otherwise rebind its build system to the importer's. */
    private fun ensureProject(model: ExternalProjectModel): Boolean {
        val existing = store.workspace.projects.firstOrNull()
        val tx = store.workspace.beginModification()
        return try {
            if (existing == null) {
                tx.addProject(model.name, model.buildSystemId, store.vfs.root())
                tx.commit()
                true
            } else {
                tx.setBuildSystem(existing.id, model.buildSystemId)
                tx.commit()
                false
            }
        } catch (e: Throwable) {
            tx.dispose()
            throw e
        }
    }

    /**
     * Write one module's snapshot into [module]. Source sets are merged (a root the build files no longer
     * mention keeps its files and its place in the tree); dependencies and facets are replaced, because the
     * build files are their source of truth. [current] is the live module when refreshing an existing one.
     */
    private fun configure(
        module: ModifiableModule,
        external: ExternalModule,
        defaultLanguageLevel: LanguageLevel,
        replaceDependencies: Boolean,
        current: Module? = null,
    ) {
        module.dirRelPath = external.dirRelPath.ifBlank { external.name }
        module.languageLevel = external.languageLevel ?: defaultLanguageLevel
        external.sdk?.let { module.sdk = it }

        for (set in external.sourceSets) {
            module.addSourceSet(SourceSetTemplate(set.name, set.scope, LinkedHashMap(set.roots)))
        }

        if (replaceDependencies && current != null) {
            // Drop what a previous sync declared and re-declare from the snapshot. SDK entries are the
            // host's, not the build files', so they stay.
            for (entry in current.dependencies) {
                if (entry is LibraryDependency || entry is PlatformDependency || entry is ModuleDependency) {
                    module.removeDependency(entry)
                }
            }
        }
        for (dependency in external.dependencies) module.addDependency(orderEntry(dependency))

        // Facets travel as table + values so an importer needs no facet class; the codec registered for the
        // table turns them back into a real Facet. An unknown table is skipped rather than failing the sync.
        val builder = module as? ModuleBuilder
        for (facet in external.facets) {
            if (store.facetCodecs.codecForTable(facet.table) == null) continue
            builder?.putFacetData(FacetData(facet.table, facet.values))
        }
    }

    private fun orderEntry(dependency: ExternalDependency) = when (dependency) {
        is ExternalLibrary -> LibraryDependency(
            LibraryRef(sanitizeLibraryName(dependency.coordinate)),
            dependency.scope,
            exported = dependency.scope == DependencyScope.API,
            exclusions = dependency.exclusions,
            variant = dependency.variant,
        )

        is ExternalModuleRef -> ModuleDependency(
            ModuleId(dependency.moduleName),
            dependency.scope,
            exported = dependency.scope == DependencyScope.API,
            variant = dependency.variant,
        )

        is ExternalPlatform -> PlatformDependency(sanitizeCoordinate(dependency.bom), dependency.scope, variant = dependency.variant)
    }
}
