package dev.ide.core.kmp

import dev.ide.lang.kotlin.build.KotlinFacet
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.ModuleDependency
import dev.ide.model.PlatformDependency
import dev.ide.model.impl.ExternalModelApplier
import dev.ide.model.impl.ModuleData
import dev.ide.model.impl.ProjectData
import dev.ide.model.impl.ProjectModelStore
import dev.ide.model.sync.ExternalDependency
import dev.ide.model.sync.ExternalFacet
import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.ExternalModule
import dev.ide.model.sync.ExternalModuleRef
import dev.ide.model.sync.ExternalPlatform
import dev.ide.model.sync.ExternalProjectModel
import dev.ide.model.sync.ExternalSourceSet

/**
 * Switches the multiplatform modules of a NATIVE project (no build scripts to re-read) to another target: each
 * module whose `[kotlin]` facet records the target is reshaped by [KmpShapes.materialize] and re-applied in place,
 * recreated so it can change type (a JVM module and an Android one are different module types). Module ids are
 * their names, so every dependency on a switched module still points at it.
 */
internal object KmpSwitch {

    /** The targets the project's multiplatform modules can be switched between, and the one they are on. */
    data class Choice(val active: String, val available: List<String>)

    fun choice(store: ProjectModelStore): Choice? {
        val facets = multiplatform(store).map { it.second }
        if (facets.isEmpty()) return null
        val available = facets.flatMap { f -> f.targets.map { it.name } }.distinct()
        if (available.size < 2) return null
        return Choice(facets.first().active?.name ?: available.first(), available)
    }

    /**
     * Reshape every module recording [target] for it. Returns the names of the modules that changed (empty when
     * none was on another target), so the caller knows whether dependencies need resolving again.
     */
    fun switch(store: ProjectModelStore, target: String, languageLevel: LanguageLevel): Set<String> {
        val project = store.data.projects.firstOrNull() ?: return emptySet()
        val changed = multiplatform(store)
            .filter { (_, facet) -> facet.activeTarget != target && facet.targets.any { it.name == target } }
            .mapNotNull { (module, _) -> KmpShapes.materialize(externalOf(project, module), target) }
        if (changed.isEmpty()) return emptySet()
        val names = changed.map { it.name }.toSet()
        ExternalModelApplier(store).apply(
            ExternalProjectModel(project.name, dev.ide.model.BuildSystemId(project.buildSystemId), changed),
            languageLevel,
            removeAbsent = false,
            recreate = names,
        )
        store.save()
        return names
    }

    private fun multiplatform(store: ProjectModelStore): List<Pair<ModuleData, KotlinFacet>> =
        store.data.projects.flatMap { it.modules }.mapNotNull { m ->
            val table = m.facets.firstOrNull { it.tomlTable == dev.ide.lang.kotlin.build.KotlinFacetCodec.tomlTable }
                ?: return@mapNotNull null
            val facet = dev.ide.lang.kotlin.build.KotlinFacetCodec.decode(table.values)
            if (facet.targets.size < 2) null else m to facet
        }

    /** A live module as the importer's shape, so [KmpShapes] (written for importers) can reshape it. */
    fun externalOf(project: ProjectData, module: ModuleData): ExternalModule {
        val names = project.modules.associate { it.id to it.name }
        return ExternalModule(
            name = module.name,
            dirRelPath = module.dirRelPath,
            typeId = module.typeId,
            languageLevel = module.languageLevel,
            outputRelPath = module.outputRelPath,
            sourceSets = module.sourceSets.map { ss ->
                ExternalSourceSet(ss.name, ss.scope, ss.contentRoots.associateTo(LinkedHashMap()) { it.dirRelPath to it.roles })
            },
            dependencies = module.dependencies.mapNotNull<_, ExternalDependency> { entry ->
                when (entry) {
                    is LibraryDependency -> ExternalLibrary(entry.library.name, entry.scope, entry.variant, entry.exclusions)
                    is ModuleDependency -> ExternalModuleRef(names[entry.target.value] ?: entry.target.value, entry.scope, entry.variant)
                    is PlatformDependency -> ExternalPlatform(entry.bom, entry.scope, entry.variant)
                    else -> null
                }
            },
            facets = module.facets.map { ExternalFacet(it.tomlTable, it.values) },
        )
    }
}
