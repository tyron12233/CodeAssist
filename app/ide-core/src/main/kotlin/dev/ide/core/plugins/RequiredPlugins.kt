package dev.ide.core.plugins

import dev.ide.build.BUILD_SYSTEM_EP
import dev.ide.model.FACET_CODEC_EP
import dev.ide.model.ModuleTypeExtensionPoint
import dev.ide.model.Project
import dev.ide.model.sync.PROJECT_IMPORTER_EP
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId

/**
 * The installed plugins a project cannot be opened (or built) without: the ones that contributed its modules'
 * types, the facets its modules carry, and the importer or build system it is owned by.
 *
 * What a store submission sends with its version, so a device that lacks one is offered the plugin before the
 * install rather than an unpacked project it cannot open. Only plugins in [installed] count: a built-in ships
 * with every copy of the IDE, so naming it would only make every project look like it needs something.
 */
object RequiredPlugins {

    fun of(projects: List<Project>, registry: ExtensionRegistry, installed: Set<String>): List<String> {
        val typeIds = projects.flatMapTo(HashSet()) { p -> p.modules.map { it.type.id } }
        val facetIds = projects.flatMapTo(HashSet()) { p -> p.modules.flatMap { m -> m.facets.all.map { it.key.id } } }
        val buildSystems = projects.mapTo(HashSet()) { it.buildSystemId }

        val owners = LinkedHashSet<PluginId>()
        registry.contributions(ModuleTypeExtensionPoint).filter { it.impl.id in typeIds }.mapTo(owners) { it.plugin }
        registry.contributions(FACET_CODEC_EP).filter { it.impl.key.id in facetIds }.mapTo(owners) { it.plugin }
        registry.contributions(PROJECT_IMPORTER_EP).filter { it.impl.id in buildSystems }.mapTo(owners) { it.plugin }
        registry.contributions(BUILD_SYSTEM_EP).filter { it.impl.id in buildSystems }.mapTo(owners) { it.plugin }

        return owners.map { it.value }.filter { it.isNotEmpty() && it in installed }.distinct().sorted()
    }
}
